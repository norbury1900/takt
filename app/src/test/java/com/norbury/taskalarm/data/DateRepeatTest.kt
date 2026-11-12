package com.norbury.taskalarm.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 按日期重复（房租 / 账单类）判定测试。
 *
 * 用户场景：2026-07-21 起每 3 个月交一次房租（7/21、10/21、1/21、4/21），
 * 2027-07-21 交完最后一次。这些用例直接跑生产代码。
 */
class DateRepeatTest {

    @Test
    fun `每月多选几天都触发`() {
        // v3.34.124：每月 1/15/31；31 号在 2 月落到月末
        val mask = RepeatRule.dayMaskOf(RepeatRule.maskOfDays(listOf(1, 15, 31)))
        assertTrue(RepeatRule.isDateDue("2026-01-01", 1, "", "2026-02-01", 0, mask))
        assertTrue(RepeatRule.isDateDue("2026-01-01", 1, "", "2026-02-15", 0, mask))
        assertTrue(RepeatRule.isDateDue("2026-01-01", 1, "", "2026-02-28", 0, mask))
        assertFalse(RepeatRule.isDateDue("2026-01-01", 1, "", "2026-02-16", 0, mask))
    }

    @Test
    fun `每年多选月份整月提醒`() {
        val months = RepeatRule.maskOfMonths(listOf(2, 8))
        val days = RepeatRule.dayMaskOf(RepeatRule.FULL_MONTH_MASK)
        assertTrue(RepeatRule.isDateDue("2026-02-01", 12, "", "2027-02-10", 0, days, months))
        assertTrue(RepeatRule.isDateDue("2026-02-01", 12, "", "2027-08-31", 0, days, months))
        assertFalse(RepeatRule.isDateDue("2026-02-01", 12, "", "2027-03-01", 0, days, months))
    }

    @Test
    fun `每年只选月份时整月都提醒`() {
        val mask = RepeatRule.dayMaskOf(RepeatRule.FULL_MONTH_MASK)
        assertTrue(RepeatRule.isDateDue("2026-02-01", 12, "", "2027-02-01", 0, mask))
        assertTrue(RepeatRule.isDateDue("2026-02-01", 12, "", "2027-02-28", 0, mask))
        assertFalse(RepeatRule.isDateDue("2026-02-01", 12, "", "2027-03-01", 0, mask))
    }

    @Test
    fun `每年只在指定月份与日期触发`() {
        // 每年 2 月 5 日：跨年命中，其他月份/日期不命中
        val mask = RepeatRule.dayMaskOf(RepeatRule.maskOfDays(listOf(5)))
        assertTrue(RepeatRule.isDateDue("2026-02-05", 12, "", "2027-02-05", 0, mask))
        assertFalse(RepeatRule.isDateDue("2026-02-05", 12, "", "2027-03-05", 0, mask))
        assertFalse(RepeatRule.isDateDue("2026-02-05", 12, "", "2027-02-06", 0, mask))
    }

    private val anchor = "2026-07-21"
    private val interval = 3
    private val end = "2027-07-21"

    /** 国庆前建档，让 10 月上旬的节假日用例都不被「隐式起点 = 建档日」挡住 */
    private val beforeOct: Long = java.util.Calendar.getInstance().apply {
        clear()
        set(2026, 9, 1, 8, 0, 0)
    }.timeInMillis

    @Test
    fun `房租到期日全部命中`() {
        val due = listOf(
            "2026-07-21", "2026-10-21", "2027-01-21", "2027-04-21", "2027-07-21"
        )
        due.forEach {
            assertTrue("$it 应该提醒", RepeatRule.isDateDue(anchor, interval, end, it))
        }
    }

    @Test
    fun `非到期日不提醒`() {
        val notDue = listOf(
            "2026-07-20",  // 锚点前一天
            "2026-08-21", "2026-09-21", "2026-11-21", "2027-02-21", "2027-06-21"
        )
        notDue.forEach {
            assertFalse("$it 不应提醒", RepeatRule.isDateDue(anchor, interval, end, it))
        }
    }

    @Test
    fun `结束日期之后不再提醒`() {
        assertFalse(RepeatRule.isDateDue(anchor, interval, end, "2027-08-21"))
        assertFalse(RepeatRule.isDateDue(anchor, interval, end, "2028-01-21"))
        // 结束当天仍要提醒（含当天）
        assertTrue(RepeatRule.isDateDue(anchor, interval, end, "2027-07-21"))
    }

    @Test
    fun `整个周期恰好 5 次`() {
        var count = 0
        var y = 2026; var m = 1
        repeat(24) {
            val key = "%04d-%02d-21".format(y, m)
            if (RepeatRule.isDateDue(anchor, interval, end, key)) count++
            m++; if (m > 12) { m = 1; y++ }
        }
        assertEquals(5, count)
    }

    @Test
    fun `月末自动裁剪到当月最后一天`() {
        // 锚点 1 月 31 日、每月一次：2 月没有 31 号 → 落到 2/28（2026 不是闰年）
        val a = "2026-01-31"
        assertTrue(RepeatRule.isDateDue(a, 1, "", "2026-02-28"))
        assertTrue(RepeatRule.isDateDue(a, 1, "", "2026-04-30"))
        assertTrue(RepeatRule.isDateDue(a, 1, "", "2026-03-31"))
        assertFalse(RepeatRule.isDateDue(a, 1, "", "2026-02-27"))
    }

    @Test
    fun `每月 7 号的场景`() {
        val a = "2026-10-07"
        assertTrue(RepeatRule.isDateDue(a, 1, "", "2026-11-07"))
        assertTrue(RepeatRule.isDateDue(a, 1, "", "2026-12-07"))
        assertFalse(RepeatRule.isDateDue(a, 1, "", "2026-11-08"))
    }

    @Test
    fun `shouldTriggerTask 按类型分发`() {
        val rent = Task(
            name = "交房租",
            repeatType = RepeatRule.TYPE_DATE,
            anchorDate = anchor,
            intervalMonths = interval,
            endDate = end
        )
        assertTrue(RepeatRule.shouldTriggerTask(rent, "2026-10-21", 4))   // 周三
        assertFalse(RepeatRule.shouldTriggerTask(rent, "2026-11-21", 6))

        // 普通任务不受日期型逻辑影响。
        // 「节假日跳过」口径 = 周末 + 法定假日：普通周日不提醒
        // 注意 createdAt 要早于所查日期：v3.34.7 起按星期任务的隐式起点 = 建档日
        // （anchorDate 为空时），默认"现在"会把 10 月上旬全挡住（见 WeekdayPeriodTest）
        val daily = Task(name = "锻炼", createdAt = beforeOct)
        assertFalse(RepeatRule.shouldTriggerTask(daily, "2026-10-11", 1))
        // 法定假日（10/1–10/7 国庆）同样不提醒
        assertFalse(RepeatRule.shouldTriggerTask(daily, "2026-10-02", 6))
        // 但调休补班的周六（2026-10-10）要上班，照常提醒
        assertTrue(RepeatRule.shouldTriggerTask(daily, "2026-10-10", 7))
        // 关掉跳过后，普通周日恢复提醒
        val dailyKeep = Task(name = "锻炼", skipHolidays = false, createdAt = beforeOct)
        assertTrue(RepeatRule.shouldTriggerTask(dailyKeep, "2026-10-11", 1))
        // 「工作日」在周日不提醒、在调休补班的周六要提醒
        val weekday = Task(name = "上班任务", repeatMask = RepeatRule.WEEKDAYS, createdAt = beforeOct)
        assertFalse(RepeatRule.shouldTriggerTask(weekday, "2026-10-11", 1))
        assertTrue(RepeatRule.shouldTriggerTask(weekday, "2026-10-10", 7))
        // 「周末」任务：普通周日提醒；法定假日也是休息日，同样提醒
        val weekend = Task(name = "休息任务", repeatMask = RepeatRule.WEEKEND, createdAt = beforeOct)
        assertTrue(RepeatRule.shouldTriggerTask(weekend, "2026-10-11", 1))
        assertTrue(RepeatRule.shouldTriggerTask(weekend, "2026-10-02", 6))
        assertFalse(RepeatRule.shouldTriggerTask(weekend, "2026-10-10", 7))
    }

    @Test
    fun `描述文案`() {
        assertEquals(
            "每 3 个月的 21 号 · 至 2027-07-21",
            RepeatRule.describeDate(anchor, interval, end)
        )
        assertEquals("每月 21 号", RepeatRule.describeDate(anchor, 1, ""))
    }

    /* ---- 按天间隔（intervalDays > 0 时优先于 intervalMonths） ---- */

    @Test
    fun `每3天命中锚点及倍数日`() {
        val a = "2026-10-01"
        assertTrue(RepeatRule.isDateDue(a, 1, "", "2026-10-01", intervalDays = 3))
        assertTrue(RepeatRule.isDateDue(a, 1, "", "2026-10-04", intervalDays = 3))
        assertTrue(RepeatRule.isDateDue(a, 1, "", "2026-10-31", intervalDays = 3)) // 30 天差
        assertFalse(RepeatRule.isDateDue(a, 1, "", "2026-10-03", intervalDays = 3))
        assertFalse(RepeatRule.isDateDue(a, 1, "", "2026-09-30", intervalDays = 3)) // 锚点前
    }

    @Test
    fun `按天也受结束日期约束`() {
        val a = "2026-10-01"
        assertTrue(RepeatRule.isDateDue(a, 1, "2026-10-07", "2026-10-07", intervalDays = 3))
        assertFalse(RepeatRule.isDateDue(a, 1, "2026-10-07", "2026-10-10", intervalDays = 3))
    }

    @Test
    fun `按天时忽略 intervalMonths`() {
        // intervalMonths=3 本不该在 10 月触发，但 intervalDays=7 生效后 10-08 命中
        assertTrue(RepeatRule.isDateDue("2026-10-01", 3, "", "2026-10-08", intervalDays = 7))
    }

    @Test
    fun `shouldTriggerTask 走按天分支`() {
        val t = Task(
            name = "每 7 天体检",
            repeatType = RepeatRule.TYPE_DATE,
            anchorDate = "2026-10-01",
            intervalDays = 7
        )
        assertTrue(RepeatRule.shouldTriggerTask(t, "2026-10-15", 4))
        assertFalse(RepeatRule.shouldTriggerTask(t, "2026-10-14", 3))
    }

    @Test
    fun `按天描述文案`() {
        assertEquals(
            "每 7 天（自 2026-10-01） · 至 2026-11-01",
            RepeatRule.describeDate("2026-10-01", 1, "2026-11-01", 7)
        )
        assertEquals("每 7 天（自 2026-10-01）", RepeatRule.describeDate("2026-10-01", 1, "", 7))
    }
}
