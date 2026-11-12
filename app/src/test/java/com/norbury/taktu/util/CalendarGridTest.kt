package com.norbury.taktu.util

import com.norbury.taktu.data.RepeatRule
import com.norbury.taktu.data.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/** 计划页的月历计算 */
class CalendarGridTest {

    @Test
    fun 每月固定42格() {
        assertEquals(42, CalendarGrid.cells(2026, 9).size)   // 2026 年 10 月
        assertEquals(42, CalendarGrid.cells(2027, 1).size)   // 2027 年 2 月（28 天）
    }

    @Test
    fun 前面补上个月的尾巴() {
        // 2026-10-01 是周四 → 前 4 格应是 9 月的 27/28/29/30
        val cells = CalendarGrid.cells(2026, 9)
        assertEquals(27, cells[0].day)
        assertFalse(cells[0].inMonth)
        assertEquals(1, cells[4].day)
        assertTrue(cells[4].inMonth)
        assertEquals("2026-10-01", cells[4].dateKey)
    }

    @Test
    fun 末尾补下个月的开头() {
        // 2026-10-01 周四、10 月 31 天 → 占 4+31=35 格，末尾再补 7 格（11/1~11/7）凑满 6 行
        val cells = CalendarGrid.cells(2026, 9)
        val last = cells.last()
        assertFalse("最后一格不该属于本月", last.inMonth)
        assertEquals("2026-11-07", last.dateKey)
        assertEquals(7, last.day)
        // 补位的第一格应是 11/1
        assertEquals("2026-11-01", cells[35].dateKey)
    }

    @Test
    fun 星期推算正确() {
        assertEquals(Calendar.SUNDAY, CalendarGrid.dowOf("2026-10-11"))
        assertEquals(Calendar.THURSDAY, CalendarGrid.dowOf("2026-10-01"))
        assertEquals(Calendar.SATURDAY, CalendarGrid.dowOf("2026-10-10"))   // 调休补班的周六
    }

    @Test
    fun 房租只在到期那天出现() {
        val rent = Task(
            name = "交房租",
            repeatType = RepeatRule.TYPE_DATE,
            anchorDate = "2026-10-20",
            intervalMonths = 3,
            endDate = "2027-07-20"
        )
        assertTrue(CalendarGrid.tasksOn(listOf(rent), "2026-10-20").any { it.name == "交房租" })
        assertTrue(CalendarGrid.tasksOn(listOf(rent), "2027-01-20").any { it.name == "交房租" })
        assertTrue(CalendarGrid.tasksOn(listOf(rent), "2026-10-19").isEmpty())
        assertTrue(CalendarGrid.tasksOn(listOf(rent), "2026-11-20").isEmpty())
        assertTrue(CalendarGrid.tasksOn(listOf(rent), "2027-10-20").isEmpty())  // 已过结束日
    }

    @Test
    fun 每天任务每天都出现() {
        // Task 默认 repeatType=TYPE_WEEKDAY 且 createdAt=运行时今天：
        // 起点兜底会挡住建档日之前的查询日，createdAt 必须钉在所查日期之前
        val daily = Task(
            name = "深蹲",
            repeatMask = RepeatRule.DAILY,
            createdAt = java.util.Date(2026 - 1900, Calendar.OCTOBER, 1, 8, 0).time
        )
        assertTrue(CalendarGrid.tasksOn(listOf(daily), "2026-10-20").isNotEmpty())
        assertTrue(CalendarGrid.tasksOn(listOf(daily), "2026-10-21").isNotEmpty())
    }

    @Test
    fun 标题格式() {
        assertEquals("2026年10月", CalendarGrid.title(2026, 9))
        assertEquals("2027年1月", CalendarGrid.title(2027, 0))
    }
}
