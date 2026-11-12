package com.norbury.taktu.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * 「重复时间段」窗口回归（v3.34.7，用户实测 bug）。
 *
 * 用户场景：穿正装 = 本周（2026-10-19 周一 ~ 10-21 周三）按周一/二/三响。
 * 计划页月历与统计趋势里却出现了**上周三（10-14）、上上周三（10-08）**——
 * 该任务上周根本不存在。根因是起点约束整条丢失（anchorDate 为空/被导入丢掉），
 * 按星期任务从"mask 命中那天起天天触发"。
 *
 * 这些用例钉住两件事：
 *  1. 起止日期落库正确时，窗口外一天都不出现；
 *  2. 起点丢了（老任务 / 导入缺 startDate）时按**建档日**兜底——
 *     任务不可能在它被创建之前就该做。
 */
class WeekdayPeriodTest {

    /** 周一/二/三 = 位 1,2,3（bitOf：周日=1 → 位 0） */
    private val monTueWed = (1 shl 1) or (1 shl 2) or (1 shl 3)

    private fun millisOf(y: Int, m0: Int, d: Int, h: Int = 10): Long =
        Calendar.getInstance().apply {
            clear()
            set(y, m0, d, h, 0, 0)
        }.timeInMillis

    /** 用户那条穿正装：本周一至周三，建档日 = 10-19 */
    private fun suitThisWeek() = Task(
        name = "穿正装",
        repeatType = RepeatRule.TYPE_WEEKDAY,
        repeatMask = monTueWed,
        anchorDate = "2026-10-19",
        endDate = "2026-10-21",
        skipHolidays = false,
        createdAt = millisOf(2026, 9, 19)
    )

    @Test
    fun 本周周一至周三全部触发() {
        val t = suitThisWeek()
        assertTrue(RepeatRule.shouldTriggerTask(t, "2026-10-19", Calendar.MONDAY))
        assertTrue(RepeatRule.shouldTriggerTask(t, "2026-10-20", Calendar.TUESDAY))
        assertTrue(RepeatRule.shouldTriggerTask(t, "2026-10-21", Calendar.WEDNESDAY))
    }

    @Test
    fun 上周与上上周的周三不触发() {
        val t = suitThisWeek()
        // 用户报的 bug：上周三、上上周三也出现了这个任务
        assertFalse(RepeatRule.shouldTriggerTask(t, "2026-10-14", Calendar.WEDNESDAY))
        assertFalse(RepeatRule.shouldTriggerTask(t, "2026-10-08", Calendar.WEDNESDAY))
        // 上周的周一/周二同样不出现
        assertFalse(RepeatRule.shouldTriggerTask(t, "2026-10-12", Calendar.MONDAY))
        assertFalse(RepeatRule.shouldTriggerTask(t, "2026-10-13", Calendar.TUESDAY))
    }

    @Test
    fun 结束当天有效结束次日无效() {
        val t = suitThisWeek()
        assertTrue(RepeatRule.shouldTriggerTask(t, "2026-10-21", Calendar.WEDNESDAY))   // 含当天
        assertFalse(RepeatRule.shouldTriggerTask(t, "2026-10-22", Calendar.THURSDAY))   // 次日
        assertFalse(RepeatRule.shouldTriggerTask(t, "2026-10-28", Calendar.WEDNESDAY))  // 下周三
    }

    @Test
    fun 窗口内的星期掩码仍然生效() {
        // 周四不在 mask 里，即使在期间内也不触发
        val t = suitThisWeek()
        assertFalse(RepeatRule.shouldTriggerTask(t, "2026-10-19", Calendar.THURSDAY))
    }

    @Test
    fun 起点丢失时按建档日兜底() {
        // 老任务：anchorDate 为空（导入丢起点 / v3.34 之前创建），建档日 = 10-19
        val t = Task(
            name = "老任务",
            repeatType = RepeatRule.TYPE_WEEKDAY,
            repeatMask = monTueWed,
            anchorDate = "",
            skipHolidays = false,
            createdAt = millisOf(2026, 9, 19)
        )
        // 建档日之前：上周三、上上周三、上周一都不该出现（修复前会天天触发）
        assertFalse(RepeatRule.shouldTriggerTask(t, "2026-10-14", Calendar.WEDNESDAY))
        assertFalse(RepeatRule.shouldTriggerTask(t, "2026-10-08", Calendar.WEDNESDAY))
        assertFalse(RepeatRule.shouldTriggerTask(t, "2026-10-12", Calendar.MONDAY))
        // 建档日当天及之后照常
        assertTrue(RepeatRule.shouldTriggerTask(t, "2026-10-19", Calendar.MONDAY))
        assertTrue(RepeatRule.shouldTriggerTask(t, "2026-10-21", Calendar.WEDNESDAY))
        assertTrue(RepeatRule.shouldTriggerTask(t, "2026-11-02", Calendar.MONDAY))
    }

    @Test
    fun 有完整起点的老任务不受兜底影响() {
        // anchorDate 是完整日期时以它为准，与建档日无关
        val t = Task(
            name = "长期任务",
            repeatType = RepeatRule.TYPE_WEEKDAY,
            repeatMask = monTueWed,
            anchorDate = "2026-06-01",
            skipHolidays = false,
            createdAt = millisOf(2026, 8, 19)   // 建档日晚于起点也认起点
        )
        assertTrue(RepeatRule.shouldTriggerTask(t, "2026-10-14", Calendar.WEDNESDAY))
        assertFalse(RepeatRule.shouldTriggerTask(t, "2026-05-31", Calendar.SUNDAY))
    }

    @Test
    fun weekdayStartKey_有起点取起点_没起点取建档日() {
        val withAnchor = suitThisWeek()
        assertEquals("2026-10-19", RepeatRule.weekdayStartKey(withAnchor))
        val legacy = withAnchor.copy(anchorDate = "")
        assertEquals("2026-10-19", RepeatRule.weekdayStartKey(legacy))
    }
}
