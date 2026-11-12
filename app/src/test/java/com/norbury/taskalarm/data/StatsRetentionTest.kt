package com.norbury.taskalarm.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 统计页「已结束任务保留一周」口径测试（v3.34.7，用户 2026-10-20 要求）。
 *
 * 过期任务一直占着统计列表，正在进行的任务很难翻到——所以结束后只留一周。
 * 只影响统计页显示，记录仍留库（历史页、趋势核算不受影响）。
 */
class StatsRetentionTest {

    private val today = "2026-10-20"

    private fun weekday(
        end: String = "",
        anchor: String = "",
        name: String = "穿正装"
    ) = Task(
        name = name,
        repeatType = RepeatRule.TYPE_WEEKDAY,
        repeatMask = (1 shl 1) or (1 shl 2) or (1 shl 3),
        anchorDate = anchor,
        endDate = end
    )

    @Test
    fun 没结束的任务一直显示() {
        assertTrue(StatsRetention.keepInStats(weekday(), today))                        // 永久
        assertTrue(StatsRetention.keepInStats(weekday(end = "2026-10-21"), today))      // 今天才结束
        assertTrue(StatsRetention.keepInStats(weekday(end = "2026-12-31"), today))      // 未到结束日
    }

    @Test
    fun 结束未过不等于已结束() {
        assertFalse(StatsRetention.isEnded(weekday(end = "2026-10-21"), today))
        assertFalse(StatsRetention.isEnded(weekday(), today))
    }

    @Test
    fun 已结束的任务七天内仍显示() {
        // 10-14 结束，今天 10-20：第 6 天，还在窗口里
        assertTrue(StatsRetention.keepInStats(weekday(end = "2026-10-14"), today))
        // 恰好第 7 天（10-13 结束）也还算「一个星期内」
        assertTrue(StatsRetention.keepInStats(weekday(end = "2026-10-13"), today))
    }

    @Test
    fun 超过一个星期不再显示() {
        // 10-12 结束 = 第 8 天，超期
        assertFalse(StatsRetention.keepInStats(weekday(end = "2026-10-12"), today))
        assertFalse(StatsRetention.keepInStats(weekday(end = "2026-10-01"), today))
    }

    @Test
    fun 一次性任务过期同样只留一周() {
        // 老一次性任务没有 endDate，「结束时间」就是 anchorDate 那天
        val once = Task(
            name = "办证",
            repeatType = RepeatRule.TYPE_ONCE,
            anchorDate = "2026-10-19",
            endDate = ""
        )
        assertTrue(StatsRetention.keepInStats(once, today))     // 昨天到期，还在窗口里
        assertFalse(StatsRetention.keepInStats(once, "2026-10-27"))   // 第 8 天，超期

        // 新数据一次性任务 endDate 已补齐为当天，同样口径
        val onceNew = once.copy(endDate = "2026-10-19")
        assertTrue(StatsRetention.keepInStats(onceNew, today))
        assertFalse(StatsRetention.keepInStats(onceNew, "2026-10-27"))
    }

    @Test
    fun 一次性任务日期还没到不属于已结束() {
        val once = Task(
            name = "编程考试",
            repeatType = RepeatRule.TYPE_ONCE,
            anchorDate = "2026-10-22"
        )
        assertFalse(StatsRetention.isEnded(once, today))
        assertTrue(StatsRetention.keepInStats(once, today))
    }

    @Test
    fun 截止线就是今天减七天() {
        assertEquals("2026-10-13", StatsRetention.cutoffKey(today))
    }
}
