package com.norbury.taktu.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 按「法定节假日」切换时间 —— 注意不是简单的周六周日：
 *  - 放假的周一至周五（如国庆）→ 休息日 → 用休息日时间
 *  - 调休补班的周六周日（如 2026-10-10 补班）→ **工作日** → 仍用主时间
 *  - 普通周末 → 休息日 → 用休息日时间
 */
class RestDayTimeTest {

    private fun task() = Task(
        name = "交房租",
        deadlineMin = 19 * 60,          // 工作日 19:00
        restDayDeadlineMin = 10 * 60    // 法定休息日 10:00
    )

    @Test
    fun 普通工作日走主时间() {
        assertEquals(19 * 60, task().deadlineOn("2026-10-20"))   // 周二
    }

    @Test
    fun 普通周末走休息日时间() {
        assertEquals(10 * 60, task().deadlineOn("2026-10-11"))   // 周日
        assertEquals(10 * 60, task().deadlineOn("2026-10-17"))   // 周六
    }

    @Test
    fun 国庆放假的周一至周五走休息日时间() {
        // 2026 国庆放假 10/1–10/7，其中 10/5 是周一
        assertEquals(10 * 60, task().deadlineOn("2026-10-05"))
        assertEquals(10 * 60, task().deadlineOn("2026-10-06"))   // 周二
    }

    @Test
    fun 调休补班的周末仍走主时间() {
        // 2026-10-10 周六，国庆调休补班 → 算工作日，不该用休息日时间
        assertEquals(19 * 60, task().deadlineOn("2026-10-10"))
    }

    @Test
    fun 没设休息日时间就始终用主时间() {
        val t = Task(name = "x", deadlineMin = 19 * 60, restDayDeadlineMin = -1)
        assertEquals(19 * 60, t.deadlineOn("2026-10-11"))        // 周日
        assertEquals(19 * 60, t.deadlineOn("2026-10-05"))        // 国庆
        assertEquals(19 * 60, t.deadlineOn("2026-10-20"))        // 工作日
    }

    @Test
    fun 导入支持休息日时间字段() {
        val json = """
            {"tasks":[{"name":"交房租","deadline":"19:00",
                       "restDayDeadline":"10:00","urgeBefore":60}]}
        """.trimIndent()
        val t = com.norbury.taktu.util.TaskImporter.parse(json).tasks[0]
        assertEquals(19 * 60, t.deadlineMin)
        assertEquals(10 * 60, t.restDayDeadlineMin)
        // 节日（国庆 10/5）用 10:00，普通工作日 10/20 用 19:00
        assertEquals(10 * 60, t.deadlineOn("2026-10-05"))
        assertEquals(19 * 60, t.deadlineOn("2026-10-20"))
    }

    @Test
    fun 催促与复查时刻跟着当天截止时间走() {
        val t = task().copy(preOffsetMin = 60, postOffsetMin = 90)
        // 工作日：19:00 截止 → 18:00 催 → 20:30 复查
        assertEquals(18 * 60, t.preAlertOn("2026-10-20"))
        assertEquals(20 * 60 + 30, t.finalCheckOn("2026-10-20"))
        // 休息日：10:00 截止 → 09:00 催 → 11:30 复查
        assertEquals(9 * 60, t.preAlertOn("2026-10-11"))
        assertEquals(11 * 60 + 30, t.finalCheckOn("2026-10-11"))
    }
}
