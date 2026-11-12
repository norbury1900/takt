package com.norbury.taskalarm.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 计划页时间段展示（Task.spanOn）：时间段 = 开始（催促）→ 目标，
 * 没开催促就退化为时间点。复查（新口径的「截止」）不拉长展示段。
 */
class TaskSpanTest {

    private fun task(
        deadlineMin: Int,
        pre: Int = Task.OFFSET_OFF,
        post: Int = Task.OFFSET_OFF
    ) = Task(id = 1, name = "t", deadlineMin = deadlineMin, preOffsetMin = pre, postOffsetMin = post)

    private val key = "2026-10-21" // 普通日，不踩法定假期

    @Test
    fun `开了催促_显示开始到目标`() {
        // 目标 19:00，催 30 分 → 18:30–19:00（复查开不开都不影响展示段）
        assertEquals("18:30–19:00", task(1140, 30, Task.OFFSET_OFF).spanOn(key))
        assertEquals("18:30–19:00", task(1140, 30, 120).spanOn(key))
    }

    @Test
    fun `没开催促_退化为时间点`() {
        assertEquals("19:00", task(1140).spanOn(key))
        // 0 = 准时（与目标同刻），也等于没拉开窗口
        assertEquals("19:00", task(1140, 0, 0).spanOn(key))
        // 只开复查（新口径的「截止」）不拉长展示段
        assertEquals("19:00", task(1140, Task.OFFSET_OFF, 60).spanOn(key))
    }

    @Test
    fun `催促跨午夜_起点回绕到前一天`() {
        // 目标 00:30，催 60 分 → 23:30–00:30
        assertEquals("23:30–00:30", task(30, 60, Task.OFFSET_OFF).spanOn(key))
    }
}
