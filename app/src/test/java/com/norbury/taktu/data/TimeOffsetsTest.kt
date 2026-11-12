package com.norbury.taktu.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 逐时刻偏移表（TimeOffsets）与逐时刻完成态的单测。
 *
 * 覆盖两件事（v3.34.21 用户反馈）：
 *  1. 每个时刻各有自己的开始 / 复查（多对多），没配的回落任务级默认值
 *  2. 一个时刻确认完**不能**把整天标成完成；全确认了才算
 */
class TimeOffsetsTest {

    private fun task(
        pre: Int = 30,
        post: Int = 120,
        spec: String = "480,1020",
        offsets: String = ""
    ) = Task(
        name = "t",
        deadlineMin = 480,
        preOffsetMin = pre,
        postOffsetMin = post,
        timeSpec = spec,
        timeOffsets = offsets
    )

    /* ---------------- 解析 / 编码 ---------------- */

    @Test
    fun `空串解析出空表`() {
        assertTrue(TimeOffsets.parse("").isEmpty())
        assertEquals("", TimeOffsets.encode(emptyMap()))
    }

    @Test
    fun `解析时刻与偏移对`() {
        val m = TimeOffsets.parse("480:90/30,1020:120/60")
        assertEquals(2, m.size)
        assertEquals(90 to 30, m[480])
        assertEquals(120 to 60, m[1020])
    }

    @Test
    fun `非法条目被跳过`() {
        // 时刻越界(9999) / 偏移非数字(abc) / 缺后半段(只有 pre) → 整条丢弃
        val m = TimeOffsets.parse("9999:10/10,480:abc/10,1020:30,600:-5/5")
        assertEquals(0, m.size)
    }

    @Test
    fun `部分非法时好条目照常解析`() {
        val m = TimeOffsets.parse("9999:10/10,480:90/30,600:-5/5,1020:5/10")
        assertEquals(setOf(480, 1020), m.keys)
    }

    @Test
    fun `编码按时刻升序`() {
        val s = TimeOffsets.encode(mapOf(1020 to (5 to 6), 480 to (1 to 2)))
        assertEquals("480:1/2,1020:5/6", s)
    }

    @Test
    fun `编码过滤非法并封顶`() {
        val big = (0 until TimeOffsets.MAX_ENTRIES + 5).associate { it to (5 to 5) }
        val s = TimeOffsets.encode(big)
        assertEquals(TimeOffsets.MAX_ENTRIES, s.split(',').size)
    }

    /* ---------------- 取值与回落 ---------------- */

    @Test
    fun `未配置的时刻回落任务级默认值`() {
        val t = task(pre = 30, post = 120, offsets = "480:90/30")
        assertEquals(30, t.preOffsetOf(1020))
        assertEquals(120, t.postOffsetOf(1020))
    }

    @Test
    fun `配置过的时刻用自己那套`() {
        val t = task(offsets = "480:90/30,1020:120/60")
        assertEquals(90, t.preOffsetOf(480))
        assertEquals(30, t.postOffsetOf(480))
        assertEquals(120, t.preOffsetOf(1020))
        assertEquals(60, t.postOffsetOf(1020))
    }

    @Test
    fun `关闭态可以逐时刻设置`() {
        // 时刻表只有 480 和 1020：给 480 单独关掉，1020 沿用任务级 30/120
        val t = task(pre = Task.OFFSET_OFF, post = Task.OFFSET_OFF, offsets = "480:-1/-1")
        assertEquals(Task.OFFSET_OFF, t.preOffsetOf(480))
        assertFalse(t.timeConfirmed(null, 0))
        // 1020 没配 → 回落任务级 -1，所以整体两个提醒都没开
        assertEquals(Task.OFFSET_OFF, t.preOffsetOf(1020))
        assertFalse(t.hasPreReminder())
        assertFalse(t.hasPostReminder())
    }

    @Test
    fun `旧单时刻任务不受偏移表影响`() {
        val t = task(pre = 45, post = 90, spec = "", offsets = "")
        assertEquals(45, t.preOffsetOf(480))
        assertEquals(90, t.postOffsetOf(480))
    }

    /* ---------------- 逐时刻换算 ---------------- */

    @Test
    fun `每个时刻算出自己的开始与复查钟点`() {
        val t = task(offsets = "480:90/30,1020:120/60")
        // 8:00 提前 90 分 = 6:30；复查 +30 = 8:30
        assertEquals(390, t.preAlertOfTime(480))
        assertEquals(510, t.finalCheckOfTime(480))
        // 17:00 提前 120 分 = 15:00；复查 +60 = 18:00
        assertEquals(900, t.preAlertOfTime(1020))
        assertEquals(1080, t.finalCheckOfTime(1020))
    }

    @Test
    fun `跨午夜的提前量照常回绕到前一天`() {
        val t = task(offsets = "30:60/0")
        // 0:30 提前 60 分 → 昨天 23:30（负数，formatMin 会回绕）
        assertEquals(-30, t.preAlertOfTime(30))
        assertEquals(30, t.finalCheckOfTime(30))
    }

    /* ---------------- 写入 / 清理 ---------------- */

    @Test
    fun `写入某时刻不影响其他时刻`() {
        val s = TimeOffsets.with("1020:10/10", 480, 90, 30, defPre = 30, defPost = 120)
        assertEquals(2, TimeOffsets.parse(s).size)
        assertEquals(90 to 30, TimeOffsets.parse(s)[480])
        assertEquals(10 to 10, TimeOffsets.parse(s)[1020])
        val s2 = TimeOffsets.with(s, 1020, 1, 2, defPre = 30, defPost = 120)
        assertEquals(1 to 2, TimeOffsets.parse(s2)[1020])
        assertEquals(90 to 30, TimeOffsets.parse(s2)[480])
    }

    @Test
    fun `填成默认值就删掉该条目（不堆冗余）`() {
        val s = TimeOffsets.with("480:90/30", 1020, 30, 120, defPre = 30, defPost = 120)
        assertEquals("480:90/30", s)
        assertFalse(TimeOffsets.has(s, 1020))
    }

    @Test
    fun `删除时刻的配置`() {
        val s = TimeOffsets.without("480:90/30,1020:10/10", 480)
        assertFalse(TimeOffsets.has(s, 480))
        assertTrue(TimeOffsets.has(s, 1020))
    }

    @Test
    fun `retain 只留还在时刻表里的`() {
        val s = TimeOffsets.retain("480:90/30,1020:10/10,1440:5/5", listOf(480, 1020))
        assertEquals(setOf(480, 1020), TimeOffsets.parse(s).keys)
    }

    @Test
    fun `应用到全部时刻`() {
        val s = TimeOffsets.applyToAll("480:90/30", listOf(480, 1020), 15, 20, defPre = 30, defPost = 120)
        val m = TimeOffsets.parse(s)
        assertEquals(setOf(480, 1020), m.keys)
        assertTrue(m.values.all { it == (15 to 20) })
    }

    @Test
    fun `应用成默认值等于清空`() {
        val s = TimeOffsets.applyToAll("480:90/30", listOf(480, 1020), 30, 120, defPre = 30, defPost = 120)
        assertEquals("", s)
    }

    @Test
    fun `范围钳制`() {
        // 开始不能晚于目标：提前量上限 = 目标
        assertEquals(480, TimeOffsets.clampPre(9999, 480))
        // 复查不能早于目标：延后量下限 = 0（-1 是「关闭」的哨兵，要保留）
        assertEquals(Task.OFFSET_OFF, TimeOffsets.clampPost(-5, 480))
        assertEquals(0, TimeOffsets.clampPost(0, 480))
        assertEquals(Task.OFFSET_OFF, TimeOffsets.clampPre(-9, 480))
        // 目标 23:55 时复查上限收到 0（23:55 之后没有更晚的档）
        assertEquals(0, TimeOffsets.clampPost(999, ClockOffset.LAST_SLOT))
    }

    /* ---------------- 逐时刻完成态 ---------------- */

    @Test
    fun `确认一个时刻不等于整天完成`() {
        val t = task()
        val rec = TaskRecord(dateKey = "2026-10-22", taskId = 1, completed = false)
            .confirmTime(0, 2)
        assertTrue(t.timeConfirmed(rec, 0))
        assertFalse(t.timeConfirmed(rec, 1))
        assertFalse(rec.completed)
        assertFalse(t.allTimesConfirmed(rec))
    }

    @Test
    fun `全部确认才算当天完成`() {
        val t = task()
        val rec = TaskRecord(dateKey = "2026-10-22", taskId = 1, completed = false)
            .confirmTime(0, 2).confirmTime(1, 2)
        assertTrue(rec.completed)
        assertTrue(t.allTimesConfirmed(rec))
    }

    @Test
    fun `确认一个时刻会把老的 completed 拉回假`() {
        // 老记录 completed=true（整体点过完成），现在只确认了第 1 个时刻 → 不算完成
        val rec = TaskRecord("2026-10-22", 1, completed = true).confirmTime(0, 2)
        assertFalse(rec.completed)
        // 全部确认后才为真
        assertTrue(rec.confirmTime(1, 2).completed)
    }

    @Test
    fun `取消一个时刻会退回未完成`() {
        val t = task()
        val rec = TaskRecord(dateKey = "2026-10-22", taskId = 1, completed = false)
            .confirmTime(0, 2).confirmTime(1, 2)
            .unconfirmTime(1, 2)
        assertTrue(rec.confirmedTimes.contains(0))
        assertFalse(rec.confirmedTimes.contains(1))
        assertFalse(rec.completed)
        assertFalse(t.allTimesConfirmed(rec))
    }

    @Test
    fun `合并清单勾选不会冲掉时刻条目`() {
        val t = task()
        val rec = TaskRecord(dateKey = "2026-10-22", taskId = 1, completed = false)
            .confirmTime(0, 2)
            .mergeCheckedItems(setOf(0, 2))
        assertTrue(rec.checkedItemIndexes.containsAll(setOf(0, 2)))
        assertTrue(rec.confirmedTimes.contains(0))
        assertFalse(t.allTimesConfirmed(rec))
    }

    @Test
    fun `清空时刻条目只动 t 条目`() {
        val rec = TaskRecord(dateKey = "2026-10-22", taskId = 1, completed = false)
            .confirmTime(0, 2)
            .mergeCheckedItems(setOf(1))
            .clearConfirmedTimes()
        assertTrue(rec.checkedItemIndexes.contains(1))
        assertTrue(rec.confirmedTimes.isEmpty())
    }

    @Test
    fun `旧单时刻任务仍按 completed 判完成`() {
        val t = task(spec = "")
        assertTrue(t.allTimesConfirmed(TaskRecord("2026-10-22", 1, completed = true)))
        assertFalse(t.allTimesConfirmed(TaskRecord("2026-10-22", 1, completed = false)))
        assertFalse(t.allTimesConfirmed(null))
    }

    @Test
    fun `升级前的老记录仍算当天完成`() {
        // v3.34.21 之前的多时刻记录只有一个整体布尔，没有 t 条目：
        // 用户点过完成 → completed=true，升级后不能变回未完成
        val t = task()
        val legacy = TaskRecord("2026-10-22", 1, completed = true)
        assertTrue(legacy.confirmedTimes.isEmpty())
        assertTrue(t.allTimesConfirmed(legacy))
        // 老的「未完成」记录也照旧
        assertFalse(t.allTimesConfirmed(TaskRecord("2026-10-22", 1, completed = false)))
    }

    @Test
    fun `有 t 条目后按新口径严格判`() {
        val t = task()
        val rec = TaskRecord("2026-10-22", 1, completed = true).confirmTime(0, 2)
        // 只确认了一个时刻 → 不算完成（哪怕 completed 字段还是老的 true）
        assertFalse(t.allTimesConfirmed(rec))
    }

    @Test
    fun `重置为未完成会同时清掉 completed`() {
        val rec = TaskRecord("2026-10-22", 1, completed = false)
            .confirmTime(0, 2).confirmTime(1, 2)
        assertTrue(rec.completed)
        val reset = rec.clearConfirmedTimes()
        assertFalse(reset.completed)
        assertTrue(reset.confirmedTimes.isEmpty())
        assertFalse(task().allTimesConfirmed(reset))
    }

    @Test
    fun `最早未确认的时刻`() {
        val t = task()
        assertEquals(0, t.earliestUnconfirmed(null))
        val one = TaskRecord("2026-10-22", 1, false).confirmTime(0, 2)
        assertEquals(1, t.earliestUnconfirmed(one))
        val all = one.confirmTime(1, 2)
        assertEquals(-1, t.earliestUnconfirmed(all))
    }

    @Test
    fun `hasPreReminder 按时刻判断而不是只看任务级`() {
        // 任务级 preOffsetMin=-1（老语义「全关」），但 1020 单独配了开启 → 仍要排催促
        val t = task(pre = Task.OFFSET_OFF, post = Task.OFFSET_OFF, offsets = "1020:30/0")
        assertTrue(t.hasPreReminder())
        assertTrue(t.hasPostReminder())
    }
}