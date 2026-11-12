package com.norbury.taktu.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 联动开关（linkClock / linkCalendar）的「显式指定 vs 未指定」区分。
 *
 * 修的 bug：合并「改这条任务」时用 OR（`新 || 旧`），
 * 导致一旦打开过就再也关不掉 —— 远端把 linkClock 改成 false 会被旧值 true 吞掉。
 * 正确语义：**JSON 显式写了才用 JSON 的值，没写才保留库里的旧值**。
 */
class LinkSpecTest {

    @Test
    fun `JSON 显式写 false 算指定过`() {
        val r = TaskImporter.parse(
            """{"tasks":[{"name":"软考","linkClock":false,"linkCalendar":true}]}"""
        )
        val spec = r.linkSpecs[0]!!
        assertTrue(spec.clockSpecified)
        assertFalse(spec.clock)
        assertTrue(spec.calendarSpecified)
        assertTrue(spec.calendar)
    }

    @Test
    fun `JSON 没写联动开关算未指定`() {
        val r = TaskImporter.parse("""{"tasks":[{"name":"软考"}]}""")
        val spec = r.linkSpecs[0]!!
        assertFalse(spec.clockSpecified)
        assertFalse(spec.calendarSpecified)
    }

    @Test
    fun `只写其中一个开关时另一个算未指定`() {
        val r = TaskImporter.parse("""{"tasks":[{"name":"软考","linkClock":true}]}""")
        val spec = r.linkSpecs[0]!!
        assertTrue(spec.clockSpecified)
        assertTrue(spec.clock)
        assertFalse(spec.calendarSpecified)
    }

    @Test
    fun `别名也算指定过`() {
        val r = TaskImporter.parse(
            """{"tasks":[{"name":"软考","syncClock":false,"小米闹钟":false}]}"""
        )
        val spec = r.linkSpecs[0]!!
        assertTrue(spec.clockSpecified)
        assertFalse(spec.clock)
    }

    @Test
    fun `linkSpecs 与 tasks 下标严格对齐`() {
        // 第一条没有名字会被跳过，后面两条要各自对上自己的规格
        val r = TaskImporter.parse(
            """{"tasks":[
                {"deadline":"08:30"},
                {"name":"甲","linkClock":true},
                {"name":"乙","linkCalendar":false}
            ]}"""
        )
        assertEquals(2, r.tasks.size)
        assertEquals(2, r.linkSpecs.size)
        assertTrue(r.linkSpecs[0]!!.clockSpecified)
        assertTrue(r.linkSpecs[1]!!.calendarSpecified)
        assertFalse(r.linkSpecs[1]!!.clockSpecified)
    }

    @Test
    fun `删除请求不占 linkSpecs 下标`() {
        val r = TaskImporter.parse(
            """{"tasks":[{"id":12,"delete":true},{"name":"甲","linkClock":true}]}"""
        )
        assertEquals(1, r.tasks.size)
        assertEquals(1, r.linkSpecs.size)
        assertTrue(r.linkSpecs[0]!!.clockSpecified)
    }

    @Test
    fun `空输入没有 linkSpecs`() {
        val r = TaskImporter.parse("")
        assertTrue(r.linkSpecs.isEmpty())
        // 空内容要给出错误提示，而不是静默成功
        assertEquals("内容为空", r.error)
    }
}
