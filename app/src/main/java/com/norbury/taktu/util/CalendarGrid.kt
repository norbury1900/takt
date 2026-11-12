package com.norbury.taktu.util

import com.norbury.taktu.data.RepeatRule
import com.norbury.taktu.data.Task
import java.util.Calendar

/**
 * 计划页的月历。纯计算（不碰数据库），可直接单测。
 *
 *  - [cells] 生成某个月的 6×7 格子（周日起，前后补上/下月的尾巴凑满 42 格）
 *  - [tasksOn] 某一天该做哪些任务（按星期查法定日历，按日期查账期）
 *  - [dowOf] 由 yyyy-MM-dd 反推星期（Calendar.SUNDAY=1 … SATURDAY=7）
 */
object CalendarGrid {

    /** 一格：显示几号、是否属于当前月、对应的日期键 */
    data class Cell(val day: Int, val inMonth: Boolean, val dateKey: String)

    private fun keyOf(year: Int, month0: Int, day: Int): String =
        "%04d-%02d-%02d".format(year, month0 + 1, day)

    fun dowOf(dateKey: String): Int {
        val p = dateKey.split("-")
        val cal = Calendar.getInstance()
        cal.set(
            p.getOrNull(0)?.toIntOrNull() ?: cal.get(Calendar.YEAR),
            (p.getOrNull(1)?.toIntOrNull() ?: 1) - 1,
            p.getOrNull(2)?.toIntOrNull() ?: 1
        )
        return cal.get(Calendar.DAY_OF_WEEK)
    }

    /** 某月的 42 格（6 行 × 7 列，周日为第一列） */
    fun cells(year: Int, month0: Int): List<Cell> {
        val first = Calendar.getInstance().apply {
            set(year, month0, 1, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        // 该月 1 号是本周第几天（1=周日），前面要补几格
        val lead = first.get(Calendar.DAY_OF_WEEK) - 1
        val daysInMonth = first.getActualMaximum(Calendar.DAY_OF_MONTH)

        val out = ArrayList<Cell>(42)
        // 上个月的尾巴
        val prev = Calendar.getInstance().apply {
            set(year, month0, 1)
            add(Calendar.DAY_OF_MONTH, -1)
        }
        val prevMax = prev.getActualMaximum(Calendar.DAY_OF_MONTH)
        for (i in lead - 1 downTo 0) {
            val d = prevMax - i
            out.add(Cell(d, false, keyOf(prev.get(Calendar.YEAR), prev.get(Calendar.MONTH), d)))
        }
        // 本月
        for (d in 1..daysInMonth) {
            out.add(Cell(d, true, keyOf(year, month0, d)))
        }
        /*
         * 下个月的开头，凑满 42 格。
         * 注意：必须用 Calendar 推真实年月再取日 —— 之前直接 keyOf(year, month0 + 1, d)，
         * 12 月会拼出 "2026-13-05" 这种非法日期键。
         * 补格只在界面上占位（不渲染数字、不可点击），见 MainActivity.buildDayCell。
         */
        val next = Calendar.getInstance().apply {
            set(year, month0, 1)
            add(Calendar.MONTH, 1)
        }
        while (out.size < 42) {
            val d = out.size - daysInMonth - lead + 1
            out.add(Cell(d, false, keyOf(next.get(Calendar.YEAR), next.get(Calendar.MONTH), d)))
        }
        return out
    }

    /** 某天该做哪些任务（含法定节假日 / 调休判定；按日期的查账期） */
    fun tasksOn(tasks: List<Task>, dateKey: String): List<Task> {
        val dow = dowOf(dateKey)
        return tasks.filter { it.enabled && RepeatRule.shouldTriggerTask(it, dateKey, dow) }
            .sortedBy { it.deadlineOn(dateKey) }
    }

    /** 「2026年10月」 */
    fun title(year: Int, month0: Int): String = "${year}年${month0 + 1}月"
}
