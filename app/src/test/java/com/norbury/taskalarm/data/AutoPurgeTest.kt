package com.norbury.taskalarm.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 过期数据自动清理（v3.34.104）：
 * 一次性任务过期超 30 天、或带结束日期的任务结束超 30 天 → 删除；
 * 没有结束日期的循环任务永远不删。
 */
class AutoPurgeTest {

    private val cutoff = "2026-10-01"   // = 今天(2026-10-31) - 30 天

    private fun once(date: String, id: Long = 1) = Task(
        id = id, name = "一次性$id",
        repeatType = RepeatRule.TYPE_ONCE, anchorDate = date
    )

    private fun weekly(endDate: String, id: Long = 2) = Task(
        id = id, name = "按星期$id",
        repeatType = RepeatRule.TYPE_WEEKDAY, endDate = endDate
    )

    @Test
    fun `一次性任务超过一个月才删除`() {
        val ids = AutoPurge.expiredTaskIds(
            listOf(once("2026-09-01", 1), once("2026-10-20", 2)), cutoff
        )
        assertEquals(listOf(1L), ids)
    }

    @Test
    fun `结束日期超过一个月的任务删除`() {
        val ids = AutoPurge.expiredTaskIds(
            listOf(weekly("2026-09-15", 1), weekly("2026-10-15", 2)), cutoff
        )
        assertEquals(listOf(1L), ids)
    }

    @Test
    fun `没有结束日期的循环任务永不删除`() {
        assertTrue(AutoPurge.expiredTaskIds(listOf(weekly("")), cutoff).isEmpty())
    }

    @Test
    fun `正好卡在截止日不删`() {
        assertTrue(AutoPurge.expiredTaskIds(listOf(once("2026-10-01")), cutoff).isEmpty())
    }
}
