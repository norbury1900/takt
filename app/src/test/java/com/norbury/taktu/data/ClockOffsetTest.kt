package com.norbury.taktu.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「钟点 ↔ 偏移量」换算与范围裁剪测试（v3.34.17）。
 *
 * 语义：编辑页改成直接选「几点几分」，但落库仍存偏移量
 * （preOffsetMin / postOffsetMin），调度链路与 JSON 契约零改动。
 */
class ClockOffsetTest {

    @Test
    fun `开始时间范围不能晚于目标时刻`() {
        val r = ClockOffset.startRange(8 * 60 + 30)
        assertEquals(0, r.first)
        assertEquals(8 * 60 + 30, r.last)
    }

    @Test
    fun `截止时间范围不能早于目标时刻`() {
        val r = ClockOffset.endRange(8 * 60 + 30)
        assertEquals(8 * 60 + 30, r.first)
        assertEquals(ClockOffset.LAST_SLOT, r.last)
    }
    @Test
    fun `目标时刻极小时范围不倒挂`() {
        // 目标 23:59 → 截止范围必须仍有元素（23:55 是最后一档）
        val r = ClockOffset.endRange(23 * 60 + 59)
        assertTrue(r.first <= r.last)
        assertEquals(ClockOffset.LAST_SLOT, r.last)
    }

    @Test
    fun `钟点反算提前量`() {
        // 目标 08:30，选 07:00 → 提前 90 分钟
        assertEquals(90, ClockOffset.startOffset(8 * 60 + 30, 7 * 60))
        // 选目标时刻本身 → 准时（0）
        assertEquals(0, ClockOffset.startOffset(8 * 60 + 30, 8 * 60 + 30))
    }

    @Test
    fun `钟点反算延后量`() {
        // 目标 08:30，选 10:30 → 延后 120 分钟
        assertEquals(120, ClockOffset.endOffset(8 * 60 + 30, 10 * 60 + 30))
        assertEquals(0, ClockOffset.endOffset(8 * 60 + 30, 8 * 60 + 30))
    }

    @Test
    fun `越界的钟点被钳到范围内`() {
        // 开始时间选了比目标还晚 → 钳到目标时刻（提前 0）
        assertEquals(0, ClockOffset.startOffset(8 * 60 + 30, 9 * 60))
        // 截止时间选了比目标还早 → 钳到目标时刻（延后 0）
        assertEquals(0, ClockOffset.endOffset(8 * 60 + 30, 7 * 60))
    }

    @Test
    fun `偏移量正反互换`() {
        val target = 8 * 60 + 30
        listOf(0, 30, 60, 90, 120).forEach { off ->
            val clock = ClockOffset.startClock(target, off)
            assertEquals(off, ClockOffset.startOffset(target, clock))
        }
        listOf(0, 30, 60, 120, 240).forEach { off ->
            val clock = ClockOffset.endClock(target, off)
            assertEquals(off, ClockOffset.endOffset(target, clock))
        }
    }

    @Test
    fun `老数据越界时只影响弹窗初值不崩`() {
        // 目标 00:30、提前 90 分钟 → 真实催促点是前一天 23:00，新范围里没有这一档
        val clock = ClockOffset.startClock(30, 90)
        assertTrue(clock in ClockOffset.startRange(30))
        assertEquals(30, ClockOffset.startOffset(30, clock))
    }

    @Test
    fun `分钟档按五分钟对齐`() {
        assertEquals(listOf(0, 5, 10), ClockOffset.minuteSlots(0, 12))
        // 起点向上对齐、终点向下对齐
        assertEquals(listOf(10, 15), ClockOffset.minuteSlots(8, 17))
        // 区间倒挂 → 空列表（不能崩）
        assertEquals(emptyList<Int>(), ClockOffset.minuteSlots(30, 20))
    }

    @Test
    fun `范围内的小时才进滚轮`() {
        // 00:00 – 08:30：8 点这一小时只到 30 分，9 点整档不可选
        val hours = ClockOffset.validHours(0, 8 * 60 + 30)
        assertEquals(0, hours.first())
        assertEquals(8, hours.last())
        assertTrue(9 !in hours)
    }

    @Test
    fun `有效小时跳过没有可选分钟的档`() {
        // 23:50 – 23:55：23 点有档（50/55），前一小时 22 点无档
        val hours = ClockOffset.validHours(23 * 60 + 50, 23 * 60 + 55)
        assertEquals(listOf(23), hours)
    }

    @Test
    fun `跨零点的开始时间仍然合法`() {
        // 目标 00:30，开始时间范围 00:00–00:30
        val hours = ClockOffset.validHours(0, 30)
        assertEquals(listOf(0), hours)
        assertEquals(listOf(0, 5, 10, 15, 20, 25, 30), ClockOffset.minuteSlots(0, 30))
    }

    /* --- minuteSlotsOfHour：分针滚轮专用，返回小时内分数（v3.34.21 修「分钟 680」） --- */

    @Test
    fun `分针档只返回小时内分数不会出现 680`() {
        // 目标 12:30 → 开始时间范围 00:00–12:30，11 点这一小时应给 0/5/…/55
        val slots = ClockOffset.minuteSlotsOfHour(11, 0, 12 * 60 + 30)
        assertEquals(0, slots.first())
        assertEquals(55, slots.last())
        assertTrue(slots.all { it in 0..59 })
    }

    @Test
    fun `分针档受范围上下界裁剪`() {
        // 范围 11:10–11:20 → 向上对齐 11:10、向下对齐 11:20
        assertEquals(listOf(10, 15, 20), ClockOffset.minuteSlotsOfHour(11, 11 * 60 + 10, 11 * 60 + 20))
        // 上界只到 11:03 → 11:00 之后没档，但 11:00 本身在范围内
        assertEquals(listOf(0), ClockOffset.minuteSlotsOfHour(11, 11 * 60, 11 * 60 + 3))
    }

    @Test
    fun `范围落在别的小时空列表`() {
        // 范围 00:00–08:30 时问 11 点 → 没有任何可选分钟
        assertEquals(emptyList<Int>(), ClockOffset.minuteSlotsOfHour(11, 0, 8 * 60 + 30))
        // 下界之后的小时同样为空
        assertEquals(emptyList<Int>(), ClockOffset.minuteSlotsOfHour(7, 8 * 60, 8 * 60 + 30))
    }
}
