package com.norbury.taskalarm.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 催促 / 复查「滚轮下标 ↔ 分钟值」映射测试（v3.26.0 起）。
 *
 * 语义：-1 = 关闭，0 = 准时（与截止同时响），>0 = 提前 / 延后的分钟数。
 * 编辑页滚轮与 [Task] 的纯函数共用同一映射，两边必须永远一致。
 */
class OffsetWheelTest {

    @Test
    fun `滚轮前两格是关闭与准时`() {
        assertEquals(Task.OFFSET_OFF, Task.offsetOfWheelIndex(0))  // 第 0 格 = 关闭
        assertEquals(0, Task.offsetOfWheelIndex(1))                // 第 1 格 = 准时
        assertEquals(5, Task.offsetOfWheelIndex(2))                // 之后每 5 分钟一格
        assertEquals(480, Task.offsetOfWheelIndex(97))             // 上限 8 小时
    }

    @Test
    fun `滚轮下标与分钟值互逆`() {
        listOf(-1, 0, 5, 10, 15, 30, 60, 90, 120, 300, 480).forEach { min ->
            assertEquals(min, Task.offsetOfWheelIndex(Task.wheelIndexOfOffset(min)))
        }
    }

    @Test
    fun `非五倍数就近取格`() {
        // 老数据里可能残留非 5 倍数的值（早期手工导入），取格不能崩
        assertEquals(30, Task.offsetOfWheelIndex(Task.wheelIndexOfOffset(28)))
        assertEquals(30, Task.offsetOfWheelIndex(Task.wheelIndexOfOffset(32)))
        assertEquals(35, Task.offsetOfWheelIndex(Task.wheelIndexOfOffset(33)))
    }

    @Test
    fun `准时提醒与截止同一时刻`() {
        val t = Task(name = "喝水", deadlineMin = 9 * 60, preOffsetMin = 0, postOffsetMin = 0)
        assertEquals(9 * 60, t.preAlertMin)
        assertEquals(9 * 60, t.finalCheckMin)
    }

    @Test
    fun `两个都关闭时没有任何响铃点`() {
        // 调度器只给 >= 0 的偏移排闹钟，-1 的任务排不出任何时间点
        val t = Task(
            name = "参考项",
            deadlineMin = 9 * 60,
            preOffsetMin = Task.OFFSET_OFF,
            postOffsetMin = Task.OFFSET_OFF
        )
        assertEquals(true, t.preOffsetMin < 0 && t.postOffsetMin < 0)
    }
}
