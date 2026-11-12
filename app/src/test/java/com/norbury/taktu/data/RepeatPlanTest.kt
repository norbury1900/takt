package com.norbury.taktu.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 统一「重复时间段」→ 落库映射（RepeatRule.resolvePlan）的回归。
 * 覆盖永久 / 仅一次 / 自定义 / 农历四种形态与三种期间内方式。
 */
class RepeatPlanTest {

    @Test
    fun 永久星期_记起点不记终点() {
        val p = RepeatRule.resolvePlan(
            startLunar = false, startKey = "2026-10-20", endKey = "",
            pattern = RepeatRule.PAT_WEEKDAY
        )
        assertEquals(RepeatRule.TYPE_WEEKDAY, p.repeatType)
        assertEquals("2026-10-20", p.anchorDate)
        assertEquals("", p.endDate)
    }

    @Test
    fun 自定义期间星期_起止都落库() {
        val p = RepeatRule.resolvePlan(
            startLunar = false, startKey = "2026-10-20", endKey = "2026-12-31",
            pattern = RepeatRule.PAT_WEEKDAY
        )
        assertEquals(RepeatRule.TYPE_WEEKDAY, p.repeatType)
        assertEquals("2026-10-20", p.anchorDate)
        assertEquals("2026-12-31", p.endDate)
    }

    @Test
    fun 仅一次_开始等于结束_落一次性() {
        val p = RepeatRule.resolvePlan(
            startLunar = false, startKey = "2026-11-11", endKey = "2026-11-11",
            pattern = RepeatRule.PAT_WEEKDAY
        )
        assertEquals(RepeatRule.TYPE_ONCE, p.repeatType)
        assertEquals("2026-11-11", p.anchorDate)
        assertEquals("2026-11-11", p.endDate)
    }

    @Test
    fun 仅一次_只在按星期方式下判定_日期方式起止同日仍是日期规则() {
        // 每 N 天且起止同日：引擎自然只在当天触发一次，不强行改判 TYPE_ONCE
        val p = RepeatRule.resolvePlan(
            startLunar = false, startKey = "2026-11-11", endKey = "2026-11-11",
            pattern = RepeatRule.PAT_EVERY_N, intervalDays = 3
        )
        assertEquals(RepeatRule.TYPE_DATE, p.repeatType)
        assertEquals(3, p.intervalDays)
    }

    @Test
    fun 每N天_永久与期间两种结束都成立() {
        val forever = RepeatRule.resolvePlan(
            false, "2026-10-20", "", RepeatRule.PAT_EVERY_N, intervalDays = 7
        )
        assertEquals(RepeatRule.TYPE_DATE, forever.repeatType)
        assertEquals(7, forever.intervalDays)
        assertEquals("", forever.endDate)

        val window = RepeatRule.resolvePlan(
            false, "2026-10-20", "2027-01-01", RepeatRule.PAT_EVERY_N, intervalDays = 7
        )
        assertEquals("2027-01-01", window.endDate)
    }

    @Test
    fun 每月N号_号就是开始日的日() {
        val p = RepeatRule.resolvePlan(
            false, "2026-07-21", "", RepeatRule.PAT_MONTHLY
        )
        assertEquals(RepeatRule.TYPE_DATE, p.repeatType)
        assertEquals("2026-07-21", p.anchorDate)
        assertEquals(1, p.intervalMonths)
        assertEquals(0, p.intervalDays)
    }

    @Test
    fun 农历开始日_只存月日_每N年进间隔() {
        val p = RepeatRule.resolvePlan(
            startLunar = true, startKey = "", endKey = "2030-01-01",
            pattern = RepeatRule.PAT_WEEKDAY, intervalYears = 3,
            lunarMonth = 1, lunarDay = 28
        )
        assertEquals(RepeatRule.TYPE_LUNAR, p.repeatType)
        assertEquals("01-28", p.anchorDate)
        assertEquals(3, p.intervalMonths)
        assertEquals(0, p.intervalDays)
        assertEquals("2030-01-01", p.endDate)
    }

    @Test
    fun 农历开始日_结束日期留空即长期() {
        val p = RepeatRule.resolvePlan(
            startLunar = true, startKey = "", endKey = "",
            pattern = RepeatRule.PAT_WEEKDAY, lunarMonth = 12, lunarDay = 30
        )
        assertEquals("12-30", p.anchorDate)
        assertEquals("", p.endDate)
    }

    @Test
    fun 农历映射_与导入端格式一致_月日补零() {
        val p = RepeatRule.resolvePlan(
            startLunar = true, startKey = "", endKey = "",
            pattern = RepeatRule.PAT_WEEKDAY, lunarMonth = 2, lunarDay = 5
        )
        assertEquals("02-05", p.anchorDate)
    }
}
