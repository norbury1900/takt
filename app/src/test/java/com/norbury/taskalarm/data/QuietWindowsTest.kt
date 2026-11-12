package com.norbury.taskalarm.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 免打扰窗口测试（v3.34.16，用户 2026-10-22 软考场景）。
 *
 * 核心场景：周六软考当天 08:30~12:30、14:30~16:30 两个窗口，
 * 落在窗口内的提醒后移到窗口结束时刻（510–750 → 750；870–990 → 990）。
 */
class QuietWindowsTest {

    /** 用户原话的窗口：2026-10-24（周六）08:30–12:30、14:30–16:30 */
    private val raw = "2026-10-24:510-750,870-990"

    @Test
    fun 解析单日多窗口() {
        val w = QuietWindows.parse(raw)
        assertEquals(2, w.size)
        assertEquals(QuietWindow("2026-10-24", 510, 750), w[0])
        assertEquals(QuietWindow("2026-10-24", 870, 990), w[1])
    }

    @Test
    fun 解析跨多日() {
        val w = QuietWindows.parse("2026-10-24:510-750;2026-10-25:540-600;2026-11-01:0-1440")
        assertEquals(3, w.size)
        assertEquals("2026-11-01", w[2].dateKey)
        assertEquals(0, w[2].startMin)
        assertEquals(1440, w[2].endMin)
    }

    @Test
    fun 空值与乱码全部容错() {
        assertTrue(QuietWindows.parse(null).isEmpty())
        assertTrue(QuietWindows.parse("").isEmpty())
        assertTrue(QuietWindows.parse("   ").isEmpty())
        // 坏日期 / 非数字 / start>=end / 越界 / 缺冒号 —— 逐段跳过不抛
        assertTrue(QuietWindows.parse("2026-10-24:510-750,abc,750-510,-5,0-2000").size == 1)
        assertTrue(QuietWindows.parse("2026-10-24;510-750").isEmpty())
        assertTrue(QuietWindows.parse("20261024:510-750").isEmpty())
        assertTrue(QuietWindows.parse(";:;;,;").isEmpty())
    }

    @Test
    fun 序列化与解析互为逆运算() {
        val w = QuietWindows.parse(raw)
        assertEquals(raw, QuietWindows.serialize(w))
        // 同日合并 + 排序：乱序输入也能得到稳定串
        val messy = listOf(
            QuietWindow("2026-10-25", 600, 660),
            QuietWindow("2026-10-24", 870, 990),
            QuietWindow("2026-10-24", 510, 750)
        )
        assertEquals("2026-10-24:510-750,870-990;2026-10-25:600-660", QuietWindows.serialize(messy))
    }

    @Test
    fun 窗口内的响铃后移到窗口尾() {
        val w = QuietWindows.parse(raw)
        val day = "2026-10-24"
        // 8:00 的催促 + 提前到 6:30？不在窗口 → 不动
        assertEquals(390, QuietWindows.shiftFor(w, day, 390))
        // 窗口内的时刻全部后移到窗口尾
        assertEquals(750, QuietWindows.shiftFor(w, day, 510))   // 8:30 整也算打扰（左闭）
        assertEquals(750, QuietWindows.shiftFor(w, day, 700))   // 11:40
        assertEquals(750, QuietWindows.shiftFor(w, day, 749))
        assertEquals(990, QuietWindows.shiftFor(w, day, 870))   // 14:30 整
        assertEquals(990, QuietWindows.shiftFor(w, day, 900))
    }

    @Test
    fun 窗口边界与窗口外不挪动() {
        val w = QuietWindows.parse(raw)
        val day = "2026-10-24"
        assertEquals(500, QuietWindows.shiftFor(w, day, 500))   // 8:20 早于窗口，原样
        assertEquals(750, QuietWindows.shiftFor(w, day, 750))   // 12:30 整已自由（右开）
        assertEquals(800, QuietWindows.shiftFor(w, day, 800))   // 午后两窗之间
        assertEquals(990, QuietWindows.shiftFor(w, day, 990))   // 16:30 整
        assertEquals(1020, QuietWindows.shiftFor(w, day, 1020)) // 晚上
        // 别的日子完全不受影响
        assertEquals(700, QuietWindows.shiftFor(w, "2026-10-23", 700))
    }

    @Test
    fun 未设置窗口时原样返回() {
        assertEquals(700, QuietWindows.shiftFor(emptyList(), "2026-10-24", 700))
    }

    @Test
    fun 重叠窗口链式后移直到稳定() {
        // 510–750 与 700–990 重叠：510 → 750 → 990（750 仍落在第二个窗口里，继续跳）
        val w = listOf(
            QuietWindow("2026-10-24", 510, 750),
            QuietWindow("2026-10-24", 700, 990)
        )
        assertEquals(990, QuietWindows.shiftFor(w, "2026-10-24", 510))
        assertEquals(990, QuietWindows.shiftFor(w, "2026-10-24", 720))
        assertEquals(990, QuietWindows.shiftFor(w, "2026-10-24", 680))
        // 不重叠的两个窗口：各跳各的，不连环
        val w2 = listOf(
            QuietWindow("2026-10-24", 510, 750),
            QuietWindow("2026-10-24", 800, 990)
        )
        assertEquals(750, QuietWindows.shiftFor(w2, "2026-10-24", 680)) // 只命中第一个
        assertEquals(990, QuietWindows.shiftFor(w2, "2026-10-24", 850))
    }

    @Test
    fun 过期窗口清理() {
        val w = QuietWindows.parse("2026-10-20:510-750;2026-10-24:510-750")
        val kept = QuietWindows.prunePast(w, "2026-10-22")
        assertEquals(1, kept.size)
        assertEquals("2026-10-24", kept[0].dateKey)
        // 当天的窗口保留
        assertEquals(2, QuietWindows.prunePast(w, "2026-10-20").size)
    }

    @Test
    fun 分钟格式化() {
        assertEquals("08:30", QuietWindows.fmtMin(510))
        assertEquals("12:30", QuietWindows.fmtMin(750))
        assertEquals("00:00", QuietWindows.fmtMin(0))
        assertEquals("16:30", QuietWindows.fmtMin(990))
    }
}
