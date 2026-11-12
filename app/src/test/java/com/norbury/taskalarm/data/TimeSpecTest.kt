package com.norbury.taskalarm.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 每天时刻表（统一响铃模型第三段）回归：
 * 固定列表 / 间隔循环的解析、展开、编码、显示，以及逐时刻确认原语。
 */
class TimeSpecTest {

    @Test
    fun `固定列表解析_升序去重_过滤非法`() {
        assertEquals(listOf(480, 1020), TimeSpec.parseFixed("480,1020"))
        assertEquals(listOf(60, 480), TimeSpec.parseFixed("480,60,480"))   // 去重 + 排序
        assertEquals(emptyList<Int>(), TimeSpec.parseFixed("abc,,99999"))  // 越界剔除
    }

    @Test
    fun `间隔循环展开_含起点含终点`() {
        // 8:00 起每 60 分到 10:00 → 8:00、9:00、10:00 三次
        assertEquals(listOf(480, 540, 600), TimeSpec.timesOfDay("480/60/600"))
        // 最小间隔 1 分（用户要求滚轮 1 分钟起步）；end < start 非法 → 空列表
        assertEquals(listOf(480, 481), TimeSpec.timesOfDay("480/1/481"))
        assertTrue(TimeSpec.timesOfDay("480/60/300").isEmpty())
    }

    @Test
    fun `间隔循环_超过单日上限被截断`() {
        // 5 分钟间隔铺满 24 小时 = 288 个，必须截到 MAX_PER_DAY
        val out = TimeSpec.timesOfDay("0/5/1439")
        assertEquals(TimeSpec.MAX_PER_DAY, out.size)
    }

    @Test
    fun `编码往返_非法参数拒绝`() {
        assertEquals("480,1020", TimeSpec.encodeFixed(listOf(1020, 480, 480)))
        assertEquals("480/60/1320", TimeSpec.encodeInterval(480, 60, 1320))
        assertEquals("480/1/481", TimeSpec.encodeInterval(480, 1, 481))   // 1 分钟间隔合法（v3.34.3 起）
        assertEquals("", TimeSpec.encodeInterval(1400, 60, 100))   // end < start
        assertEquals("", TimeSpec.encodeInterval(480, 0, 600))     // 间隔 0 低于下限
        assertNull(TimeSpec.parseInterval("480/0/600"))
    }

    @Test
    fun `显示文案`() {
        assertEquals("08:00、17:00", TimeSpec.display("480,1020"))
        assertEquals("08:00 起每 60 分至 22:00", TimeSpec.display("480/60/1320"))
        assertEquals("", TimeSpec.display(""))
    }

    @Test
    fun `逐时刻确认_全部确认才算完成`() {
        val base = TaskRecord(dateKey = "2026-10-20", taskId = 1, completed = false)
        val step1 = base.confirmTime(0, 2)
        assertFalse(step1.completed)
        assertTrue(step1.confirmedTimes.contains(0))
        val step2 = step1.confirmTime(1, 2)
        assertTrue(step2.completed)   // 两个时刻都确认 → 当天完成

        // 清单条目（纯数字）与时刻条目（t 前缀）互不干扰
        val mixed = base.copy(checkedItems = "0,2").confirmTime(1, 3)
        assertTrue(mixed.checkedItemIndexes.containsAll(setOf(0, 2)))
        assertTrue(mixed.confirmedTimes.contains(1))
        assertFalse(mixed.completed)
    }

    @Test
    fun `Task_timesOn_旧任务回落单时刻`() {
        val legacy = Task(name = "旧任务", deadlineMin = 1235, timeSpec = "")
        assertEquals(listOf(1235), legacy.timesOn("2026-10-20"))
        assertTrue(!legacy.isMultiTime)
        val multi = Task(name = "新任务", deadlineMin = 480, timeSpec = "480,1020")
        assertEquals(listOf(480, 1020), multi.timesOn("2026-10-20"))
        assertTrue(multi.isMultiTime)
    }
}
