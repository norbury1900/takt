package com.norbury.taskalarm.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 打卡统一写入口的完成口径（v3.34.114）。
 * 这里只测纯函数 [CheckIn.resolve]：多时刻（含择一）必须按 allTimesConfirmed 重算，
 * 单时刻任务保持调用方给的完成态。
 */
class CheckInTest {

    private val today = "2026-10-22"

    private fun rec(checked: String = "", completed: Boolean = false) =
        TaskRecord(dateKey = today, taskId = 1L, completed = completed, checkedItems = checked)

    private fun multiTask(either: Boolean) = Task(
        id = 1L,
        name = "点外卖",
        deadlineMin = 685,
        timeSpec = "685,1045",
        eitherMode = either,
        createdAt = 1_700_000_000_000L
    )

    @Test
    fun `择一提醒确认一个时刻即整条完成`() {
        val r = CheckIn.resolve(multiTask(either = true), rec(checked = "t0"))
        assertTrue(r.completed)
    }

    @Test
    fun `普通多时刻只确认一个不算完成`() {
        val t = multiTask(either = false)
        assertFalse(CheckIn.resolve(t, rec(checked = "t0")).completed)
        assertTrue(CheckIn.resolve(t, rec(checked = "t0,t1")).completed)
    }

    @Test
    fun `单时刻任务保留调用方给的完成态`() {
        val t = Task(
            id = 1L, name = "晨会", deadlineMin = 520, createdAt = 1_700_000_000_000L
        )
        assertTrue(CheckIn.resolve(t, rec(completed = true)).completed)
        assertFalse(CheckIn.resolve(t, rec(completed = false)).completed)
    }
}
