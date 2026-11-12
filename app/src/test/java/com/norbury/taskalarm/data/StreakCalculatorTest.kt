package com.norbury.taskalarm.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * 完成率口径测试：重点是"窗口天数"要对得上标签。
 *
 * 曾经的 bug：完成率分母只数"创建以来的计划日"，于是创建 3 天的任务
 * 3/3 = 100%，界面却标着"30天完成率"，用户以为统计坏了。
 * 现在 StreakResult.windowDays 如实反映覆盖天数，界面据此改标签。
 *
 * 注意：这里统一用 skipHolidays = false，让"每天"任务每天都算应做，
 * 计数不随法定节假日浮动 —— 否则测试会随日历变化而时红时绿。
 */
class StreakCalculatorTest {

    /** 造一个 createdAt 为 n 天前、每天都应做的任务 */
    private fun taskDaysAgo(daysAgo: Int): Task {
        val cal = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, -daysAgo)
            set(Calendar.HOUR_OF_DAY, 8)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return Task(name = "测试", skipHolidays = false, createdAt = cal.timeInMillis)
    }

    /** 近 n 天（含今天）全部完成的记录表 */
    private fun doneFor(n: Int): Map<String, Boolean> {
        val cal = Calendar.getInstance()
        val out = HashMap<String, Boolean>()
        for (i in 0 until n) {
            out[AppDatabase.keyOf(cal.time)] = true
            cal.add(Calendar.DAY_OF_YEAR, -1)
        }
        return out
    }

    @Test
    fun `账期任务提前完成也算这一期完成`() {
        // 锚点在 5 天后：本期还没到，但用户提前交了房租，记录落在那个未来的到期日上
        val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 5) }
        val anchor = AppDatabase.keyOf(cal.time)
        val t = Task(
            name = "交房租",
            repeatType = RepeatRule.TYPE_DATE,
            anchorDate = anchor,
            intervalMonths = 1,
            createdAt = System.currentTimeMillis()
        )

        // 没交：本期还没到期，不该算成"欠着"
        val none = StreakCalculator.calculate(t, emptyMap())
        assertEquals(0, none.expectedAll)
        assertFalse("还没到期就不该显示完成率", StreakCalculator.hasData(none))

        // 提前交：这一期算结清，完成率 100%
        val paid = StreakCalculator.calculate(t, mapOf(anchor to true))
        assertEquals(1, paid.expectedAll)
        assertEquals(1, paid.doneAll)
        assertEquals(100, StreakCalculator.completionRate(paid))
        assertEquals(1, paid.current)
    }

    @Test
    fun `新任务三天全完成是百分之百但窗口只有三天`() {
        val t = taskDaysAgo(2)   // 今天 + 昨天 + 前天 = 3 天
        val r = StreakCalculator.calculate(t, doneFor(3))
        assertEquals(3, r.windowDays)
        assertEquals(3, r.expectedDays30)
        assertEquals(3, r.doneDays30)
        assertEquals(100, StreakCalculator.completionRate(r))
    }

    @Test
    fun `老任务窗口封顶三十天`() {
        val t = taskDaysAgo(100)
        val r = StreakCalculator.calculate(t, doneFor(0))
        assertEquals(30, r.windowDays)
        assertEquals(30, r.expectedDays30)
        assertEquals(0, r.doneDays30)
        assertEquals(0, StreakCalculator.completionRate(r))
    }

    @Test
    fun `三十天里只做三天完成率是十分之一`() {
        val t = taskDaysAgo(40)
        val r = StreakCalculator.calculate(t, doneFor(3))
        assertEquals(30, r.windowDays)
        assertEquals(30, r.expectedDays30)
        assertEquals(3, r.doneDays30)
        assertEquals(10, StreakCalculator.completionRate(r))
    }

    @Test
    fun `今天没做不算断连续`() {
        val t = taskDaysAgo(5)
        val rec = doneFor(5).toMutableMap()
        rec.remove(AppDatabase.todayKey())     // 今天没做
        val r = StreakCalculator.calculate(t, rec)
        assertFalse(r.doneToday)
        // 昨天往前 4 天连着做 → 当前连续 4（不含今天）
        assertEquals(4, r.current)
        // 窗口 = 创建以来 6 个日历天，其中昨天往前 4 天做了、创建当天没做
        assertEquals(6, r.windowDays)
        assertEquals(6, r.expectedDays30)
        assertEquals(4, r.doneDays30)
    }

    @Test
    fun `中断后最长纪录保留`() {
        val t = taskDaysAgo(10)
        val rec = HashMap<String, Boolean>()
        val cal = Calendar.getInstance()
        // 今天往前：做 3 天（今天、昨天、前天），空 1 天，再做 5 天
        for (i in 0 until 3) rec[AppDatabase.keyOf(cal.time)] = true.also { cal.add(Calendar.DAY_OF_YEAR, -1) }
        cal.add(Calendar.DAY_OF_YEAR, -1)      // 断一天
        for (i in 0 until 5) rec[AppDatabase.keyOf(cal.time)] = true.also { cal.add(Calendar.DAY_OF_YEAR, -1) }
        val r = StreakCalculator.calculate(t, rec)
        assertEquals(5, r.longest)
        assertEquals(3, r.current)
        assertTrue(r.current < r.longest)
    }

    @Test
    fun `周末任务不会每天都应做`() {
        // 周末任务的窗口与"每天"同为 14 天，但应做天数必须明显少于 14
        val t = taskDaysAgo(13).copy(repeatMask = RepeatRule.WEEKEND)
        val r = StreakCalculator.calculate(t, emptyMap())
        assertEquals(14, r.windowDays)
        assertTrue(
            "应做天数应落在 1~14 之间，实际 ${r.expectedDays30}",
            r.expectedDays30 in 1..14
        )
        assertTrue(r.expectedDays30 < 14)
        assertEquals(0, StreakCalculator.completionRate(r))
    }
}
