package com.norbury.taskalarm.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 桌面小部件「迷你时间轴」的数据层（v3.34.28）。
 *
 * 口径与 App 内部保持一致：多时刻每时刻一行、完成态按 confirmedTimes 判
 * （老记录回落 completed）、开始=目标-提前量、结束=目标+延后量。
 */
class WidgetTimelineTest {

    private val today = "2026-10-22"

    private fun rec(
        id: Long,
        completed: Boolean = false,
        checked: String = "",
        doneCount: Int = 0
    ) = TaskRecord(
        dateKey = today,
        taskId = id,
        completed = completed,
        doneCount = doneCount,
        checkedItems = checked
    )

    /** 单时刻任务：目标 08:40 */
    private fun single(
        id: Long = 1L,
        name: String = "晨会",
        deadline: Int = 520,
        pre: Int = 30,
        post: Int = 120,
        targetCount: Int = 0,
        doneCount: Int = 0,
        items: String = ""
    ) = Task(
        id = id,
        name = name,
        deadlineMin = deadline,
        preOffsetMin = pre,
        postOffsetMin = post,
        targetCount = targetCount,
        doneCount = doneCount,
        items = items,
        timeSpec = "",
        createdAt = 1_700_000_000_000L
    )

    private fun model(
        vararg tasks: Task,
        records: Map<Long, TaskRecord?> = emptyMap(),
        now: Int
    ) = buildTimeline(tasks.toList(), records, today, now)

    @Test
    fun `单时刻任务展开成一行区间正确`() {
        val m = model(single(), now = 8 * 60)
        assertEquals(1, m.entries.size)
        val e = m.entries[0]
        assertEquals(520, e.timeMin)
        assertEquals(490, e.startMin) // 08:40 - 30
        assertEquals(640, e.endMin)   // 08:40 + 120
        assertFalse(e.done)
        assertFalse(e.urgent)  // 08:00 还没到 08:10
        assertFalse(e.overdue)
        assertEquals(1, m.total)
        assertEquals(0, m.doneCount)
        assertEquals(1L, m.nextUndone?.taskId)
    }

    @Test
    fun `催促关闭时开始时刻等于目标`() {
        val m = model(single(pre = -1), now = 8 * 60)
        assertEquals(520, m.entries[0].startMin)
    }

    @Test
    fun `复查关闭时结束等于目标且到点即超时`() {
        // post=-1：没有复查，结束=目标 08:40
        val m = model(single(post = -1), now = 8 * 60 + 40)
        val e = m.entries[0]
        assertEquals(520, e.endMin)
        assertTrue(e.overdue)
        assertFalse(e.urgent) // start==end 无区间，不存在「进行中」
        // 差一分钟还没到催促点（08:09 < 08:10）：两者都不成立
        val m2 = model(single(post = -1), now = 8 * 60 + 9)
        assertFalse(m2.entries[0].urgent)
        assertFalse(m2.entries[0].overdue)
    }

    @Test
    fun `催促区间边界按半开区间判`() {
        val t = single()
        val a = model(t, now = 490) // 08:10 开始催促那一刻
        assertTrue(a.entries[0].urgent)
        assertFalse(a.entries[0].overdue)
        val b = model(t, now = 639) // 10:39 仍在区间内
        assertTrue(b.entries[0].urgent)
        val c = model(t, now = 640) // 10:40 复查点：区间外，转超时
        assertFalse(c.entries[0].urgent)
        assertTrue(c.entries[0].overdue)
    }

    @Test
    fun `多时刻任务每个时刻一行`() {
        val t = Task(
            name = "喝水",
            deadlineMin = 685,
            preOffsetMin = 30,
            postOffsetMin = 120,
            timeSpec = "685,1045",
            createdAt = 1_700_000_000_000L
        )
        // 11:25 已确认（t0），17:25 没有
        val m = model(t, records = mapOf(t.id to rec(t.id, checked = "t0")), now = 12 * 60)
        assertEquals(2, m.entries.size)
        val (e0, e1) = m.entries
        assertEquals(655, e0.startMin)  // 11:25 - 30
        assertEquals(805, e0.endMin)    // 11:25 + 120
        assertEquals(1015, e1.startMin) // 17:25 - 30
        assertTrue(e0.done)
        assertFalse(e1.done)
        assertEquals("时刻 1/2", e0.sub)
        assertEquals("时刻 2/2", e1.sub)
        // 任务级：只确认了一个时刻，不算完成
        assertEquals(1, m.total)
        assertEquals(0, m.doneCount)
        assertEquals(1045, m.nextUndone?.timeMin)
    }

    @Test
    fun `多时刻全部确认后当天完成`() {
        val t = Task(
            name = "喝水",
            deadlineMin = 685,
            timeSpec = "685,1045",
            createdAt = 1_700_000_000_000L
        )
        val m = model(
            t,
            records = mapOf(t.id to rec(t.id, completed = true, checked = "t0,t1")),
            now = 19 * 60
        )
        assertTrue(m.entries.all { it.done })
        assertEquals(1, m.doneCount)
        assertNull(m.nextUndone)
    }

    @Test
    fun `择一提醒任一时刻确认即整条完成`() {
        // v3.34.111 用户实测：择一提醒任务在组件里确认一个时刻，不算完成、胶囊还在
        val t = Task(
            name = "点外卖",
            deadlineMin = 685,
            timeSpec = "685,1045",
            eitherMode = true,
            createdAt = 1_700_000_000_000L
        )
        val m = model(t, records = mapOf(t.id to rec(t.id, checked = "t0")), now = 12 * 60)
        assertEquals(2, m.entries.size)
        assertTrue(m.entries.all { it.done })
        assertEquals(1, m.doneCount)
        assertNull(m.nextUndone)
    }

    @Test
    fun `老记录无t条目时按completed回落`() {
        // v3.34.21 升级兼容：多时刻任务的老记录只有整体布尔，无 t 条目
        val t = Task(
            name = "喝水",
            deadlineMin = 685,
            timeSpec = "685,1045",
            createdAt = 1_700_000_000_000L
        )
        val m = model(
            t,
            records = mapOf(t.id to rec(t.id, completed = true)),
            now = 12 * 60
        )
        assertTrue(m.entries.all { it.done })
        assertEquals(1, m.doneCount)
        assertNull(m.nextUndone)
    }

    @Test
    fun `组数与清单进度拼进后缀`() {
        val t = single(
            targetCount = 3,
            doneCount = 1,
            items = "买牛奶\n买菜"
        )
        val m = model(
            t,
            records = mapOf(t.id to rec(t.id, doneCount = 1, checked = "0")),
            now = 8 * 60
        )
        assertEquals("1/3 · 清单 1/2", m.entries[0].sub)
    }

    @Test
    fun `nextUndone取时间最早的未完成行`() {
        val a = single(id = 1L, name = "已做", deadline = 480, pre = 0, post = 0)
        val b = single(id = 2L, name = "没做", deadline = 700, pre = 0, post = 0)
        val m = model(
            a, b,
            records = mapOf(1L to rec(1L, completed = true)),
            now = 7 * 60
        )
        assertEquals(2L, m.nextUndone?.taskId)
        assertEquals(1, m.doneCount)
        assertEquals(2, m.total)
    }

    @Test
    fun `布局把同刻的行按最小行距推开`() {
        // 两条都从 08:10 开始，第二行应被推到距第一行 30dp
        val m = model(
            single(id = 1L, deadline = 520),
            single(id = 2L, name = "第二件事", deadline = 520),
            now = 8 * 60
        )
        val lay = layout(m, heightDp = 200f)
        val y0 = lay.placed[0].y
        val y1 = lay.placed[1].y
        assertEquals(30f, y1 - y0, 0.001f)
        assertEquals("08:10", lay.placed[0].timeLabel)
    }

    @Test
    fun `时间窗至少90分钟且包含当前时刻`() {
        // 只有 11:40 一个点（提前/延后都关），跨度会被撑到 90 分钟
        val m = model(single(deadline = 700, pre = -1, post = -1), now = 700)
        val lay = layout(m, heightDp = 200f)
        assertEquals(655, lay.windowStart) // 11:40 - 45
        assertEquals(745, lay.windowEnd)   // 11:40 + 45
        assertTrue(lay.nowY != null)
        assertFalse(lay.empty)
    }

    @Test
    fun `空模型直接走空态`() {
        val m = buildTimeline(emptyList(), emptyMap(), today, 600)
        val lay = layout(m, heightDp = 200f)
        assertTrue(lay.empty)
        assertTrue(lay.placed.isEmpty())
        assertNull(lay.nowY)
    }

    @Test
    fun `跨午夜的开始时刻被夹回可视区`() {
        // 目标 00:20、提前 60 分 → 开始 = -40（前一天 23:20）
        val m = model(single(deadline = 20, pre = 60, post = 30), now = 23 * 60 + 30)
        val lay = layout(m, heightDp = 200f)
        assertTrue(lay.placed[0].y >= 12f) // 不小于 padTop
        assertTrue(lay.nowY != null)
    }

    @Test
    fun `行多挤出底部时不再堆叠在底边`() {
        // v3.34.60：4 条同在 20:00 的任务，高度 150dp —— 旧算法单向推开后
        // coerceIn 把第 3、4 行钳到底边同一个 y（重叠）；现在应全程行距 ≥ minGap
        val tasks = (1L..4L).map { single(id = it, name = "晚$it", deadline = 1200, pre = 0, post = 0) }
        val lay = layout(model(*tasks.toTypedArray(), now = 1200), heightDp = 150f)
        val ys = lay.placed.map { it.y }
        assertTrue(ys.zipWithNext().all { (a, b) -> b - a >= 30f - 0.001f })
        assertTrue(ys.last() <= 150f - 12f)  // 不越底边（padBottom=12）
        assertTrue(ys.first() >= 12f)        // 不越顶边
    }

    @Test
    fun `行数超过容量时按均分行距铺满不重叠`() {
        // 6 条同刻任务、150dp：minGap 收紧为 avail/n=21dp，仍然两两不重叠
        val tasks = (1L..6L).map { single(id = it, name = "任务$it", deadline = 520, pre = 0, post = 0) }
        val lay = layout(model(*tasks.toTypedArray(), now = 8 * 60), heightDp = 150f)
        val ys = lay.placed.map { it.y }
        assertTrue(ys.zipWithNext().all { (a, b) -> b - a >= 21f - 0.001f })
        assertTrue(ys.last() <= 150f - 12f)
    }

    @Test
    fun `现在线在未来的任务之前`() {
        // v3.34.97 用户实测：13:00 时「现在」线跑到了 17:10 购物后面。
        // 布局算法本身必须把 now 画在 17:10 上方（线上偏来自组件不刷新，另修刷新节拍）
        val m = model(single(name = "购物", deadline = 17 * 60 + 10, pre = 0, post = 0), now = 13 * 60)
        val lay = layout(m, heightDp = 220f)
        val nowY = lay.nowY
        assertTrue(nowY != null)
        assertTrue("现在线($nowY) 应在 17:10 行(${lay.placed[0].y}) 上方", nowY!! < lay.placed[0].y)
    }
}
