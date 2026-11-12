package com.norbury.taskalarm.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 多时刻任务的状态判定（v3.34.22）。
 *
 * 背景：以前状态 pill 拿**首个时刻**算截止/复查，于是喝水任务（11:25 / 17:25）
 * 上午那次过了就整条显示「已过截止 / 已超时未完成」，可 17:25 那次还没到。
 */
class TaskStatusTest {

    private fun task(
        spec: String = "685,1045",
        pre: Int = 30,
        post: Int = 120
    ) = Task(
        name = "喝水",
        deadlineMin = 685,
        preOffsetMin = pre,
        postOffsetMin = post,
        timeSpec = spec,
        createdAt = 1_700_000_000_000L
    )

    private val today = "2026-10-22"

    @Test
    fun `单时刻任务状态与改动前一致`() {
        val t = task(spec = "", pre = 30, post = 120)
        // 目标 11:25，催促 10:55，复查 13:25
        assertEquals(TaskStatus.Kind.WAITING, TaskStatus.kind(t, emptySet(), today, 10 * 60))
        assertEquals(TaskStatus.Kind.URGING, TaskStatus.kind(t, emptySet(), today, 11 * 60))
        // 催促窗口会覆盖截止点（12:00 仍在 10:55–13:25 的催促区间里）—— 与改动前一致
        assertEquals(TaskStatus.Kind.URGING, TaskStatus.kind(t, emptySet(), today, 12 * 60))
        assertEquals(TaskStatus.Kind.OVERDUE, TaskStatus.kind(t, emptySet(), today, 13 * 60 + 30))
    }

    @Test
    fun `关闭复查时不会提前判超时`() {
        val t = task(spec = "", pre = 30, post = -1)
        // 复查关闭：没有超时态，只有催促（10:55 起）与截止（11:25）
        assertEquals(TaskStatus.Kind.WAITING, TaskStatus.kind(t, emptySet(), today, 10 * 60))
        assertEquals(TaskStatus.Kind.URGING, TaskStatus.kind(t, emptySet(), today, 11 * 60))
    }

    @Test
    fun `催促关闭时到点即过截止`() {
        val t = task(spec = "", pre = -1, post = 120)
        // 催促关闭 → 11:25 一到就是「已过截止」（原逻辑：pre>0 才进催促态）
        assertEquals(TaskStatus.Kind.WAITING, TaskStatus.kind(t, emptySet(), today, 11 * 60))
        assertEquals(TaskStatus.Kind.PAST_DEADLINE, TaskStatus.kind(t, emptySet(), today, 12 * 60))
    }

    @Test
    fun `上午确认后下午还没到时不再显示过期`() {
        val t = task()
        // 11:25（index 0）已确认，现在 12:00 —— 17:25 那次还没到
        val st = TaskStatus.kind(t, setOf(0), today, 12 * 60)
        assertEquals(TaskStatus.Kind.WAITING, st)
    }

    @Test
    fun `全部确认后由调用方走已完成分支`() {
        val t = task()
        // 全确认 → focusTime 兜底给最后一个；kind 不再是「等待中」，
        // 但调用方（MainActivity）会先走 isDone 分支，这里只保证不抛异常
        assertEquals(1045, TaskStatus.focusTime(t, setOf(0, 1), today, 18 * 60))
        assertEquals(
            TaskStatus.Kind.URGING,
            TaskStatus.kind(t, setOf(0, 1), today, 18 * 60)
        )
    }

    @Test
    fun `漏做已过的时刻优先提示超时`() {
        val t = task()
        // 两个都没确认，现在 18:00 —— 11:25 和 17:25 都过了，取最晚的 17:25 算状态
        assertEquals(1045, TaskStatus.focusTime(t, emptySet(), today, 18 * 60))
        // 17:25 的催促窗口 16:55–19:25 → 18:00 仍在催促区间
        assertEquals(TaskStatus.Kind.URGING, TaskStatus.kind(t, emptySet(), today, 18 * 60))
        // 19:30 → 过了复查点 → 超时
        assertEquals(TaskStatus.Kind.OVERDUE, TaskStatus.kind(t, emptySet(), today, 19 * 60 + 30))
    }

    @Test
    fun `焦点时刻的选择`() {
        val t = task()
        // 一个都没确认、现在 10:00 → 还没到点，取最近的未确认时刻 11:25
        assertEquals(685, TaskStatus.focusTime(t, emptySet(), today, 10 * 60))
        // 上午确认了 → 取下午的 17:25
        assertEquals(1045, TaskStatus.focusTime(t, setOf(0), today, 12 * 60))
        // 全确认 → 兜底最后一个
        assertEquals(1045, TaskStatus.focusTime(t, setOf(0, 1), today, 12 * 60))
    }

    @Test
    fun `逐时刻偏移各算各的`() {
        // 11:25 提前 30 分、17:25 提前 0 分（准时）
        val t = task(pre = 30).copy(timeOffsets = "685:30/120,1045:0/120")
        // 17:25 那个时刻没有提前量 → 不该进入催促态
        assertEquals(TaskStatus.Kind.WAITING, TaskStatus.kind(t, setOf(0), today, 17 * 60))
        // 11:25 那个时刻有提前 30 分 → 10:55 就进入催促态
        assertEquals(TaskStatus.Kind.URGING, TaskStatus.kind(t, setOf(1), today, 11 * 60))
    }
}
