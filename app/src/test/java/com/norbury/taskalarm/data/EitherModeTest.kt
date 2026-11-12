package com.norbury.taskalarm.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 择一提醒（v3.34.96）：多时刻任务确认任一个时刻即算当天完成。
 * 用例来自真实需求：晚饭 17:10（点外卖）/ 18:00（回家做）——
 * 17:10 确认后 18:00 的闹钟要被跳过；不确认则 18:00 照常响。
 */
class EitherModeTest {

    private fun task(either: Boolean) = Task(
        name = "晚饭",
        timeSpec = "1030,1080",   // 17:10 / 18:00
        eitherMode = either
    )

    private fun record(vararg confirmed: Int) = TaskRecord(
        dateKey = "2026-10-29",
        taskId = 1L,
        completed = false,
        checkedItems = confirmed.joinToString(",") { "t$it" }
    )

    @Test
    fun `择一任务确认任一个时刻即算完成`() {
        assertTrue(task(either = true).allTimesConfirmed(record(0)))
        assertTrue(task(either = true).allTimesConfirmed(record(1)))
    }

    @Test
    fun `择一任务没确认任何时刻不算完成`() {
        assertFalse(task(either = true).allTimesConfirmed(record()))
        assertFalse(task(either = true).allTimesConfirmed(null))
    }

    @Test
    fun `普通多时刻任务仍需全部确认`() {
        assertFalse(task(either = false).allTimesConfirmed(record(0)))
        assertTrue(task(either = false).allTimesConfirmed(record(0, 1)))
    }
}
