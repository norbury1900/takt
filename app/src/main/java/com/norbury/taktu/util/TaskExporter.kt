package com.norbury.taktu.util

import com.norbury.taktu.data.RepeatRule
import com.norbury.taktu.data.Task
import org.json.JSONArray
import org.json.JSONObject

/**
 * 任务导出器：把数据库里的任务写成 JSON 文本（导入的逆操作）。
 *
 * 与 [TaskImporter] 的 v7 格式**逐字段对称** —— 导出的文件可以直接再导回来
 * （round-trip 有单测保证），也可以发给别人 / 存档 / 换机后恢复。
 *
 * 写法约定：
 *  - 顶层 {"version": 5, "tasks": [...]}
 *  - 时间一律 "HH:MM"；休息日截止时间为 -1 时省略（导入端缺省即"与主时间相同"）
 *  - urgeBefore / recheckAfter：**-1 = 关闭，0 = 准时（与截止同时响），>0 = 提前/延后分钟数**
 *    （App 内 v3.34.17 起用绝对钟点编辑这两个时间，但存储与契约仍是偏移量：
 *      开始钟点 = 目标时刻 - urgeBefore，截止钟点 = 目标时刻 + recheckAfter）
 *  - 多时刻任务另有 timeOffsets 数组，逐时刻给各自的 urgeBefore / recheckAfter；
 *    为空（统一偏移）时省略该字段
 *  - 优先级写成 "高" / "中" / "低"，「中」是默认值所以省略不写
 *  - 清单写成数组 ["牛奶 ×2", "鸡胸肉"]；空清单省略
 *  - 按日期重复写成 repeat 对象 {"type":"date", ...}；按星期写成
 *    daily / weekdays / weekend 或星期名数组
 */
object TaskExporter {

    /** 导出全部任务；空列表返回空 tasks 数组（调用方一般会先判断非空） */
    fun toJson(tasks: List<Task>): String {
        val arr = JSONArray()
        tasks.forEach { arr.put(taskToJson(it)) }
        return JSONObject()
            .put("version", 5)
            .put("tasks", arr)
            .toString(2)
    }

    private fun taskToJson(t: Task): JSONObject {
        val o = JSONObject()
        // 带上 id：远端 AI 靠它指定「改哪条 / 删哪条」；文件导入端看到库里没有这个 id 就当新增
        if (t.id > 0) o.put("id", t.id)
        o.put("name", t.name)
        if (t.targetCount > 0) o.put("targetCount", t.targetCount)
        if (t.repsPerSet > 0) o.put("repsPerSet", t.repsPerSet)
        o.put("deadline", Task.formatMin(t.deadlineMin))
        // 每日触发时刻表（v5）：非空时导出，数组 = 固定时刻、对象 = 间隔循环；
        // deadline 仍然导出（= 首时刻），老版本 App 读到也能正常显示单时刻
        if (t.timeSpec.isNotBlank()) {
            if (com.norbury.taktu.data.TimeSpec.isInterval(t.timeSpec)) {
                val (s, step, e) = com.norbury.taktu.data.TimeSpec.parseInterval(t.timeSpec)!!
                o.put(
                    "times",
                    JSONObject()
                        .put("start", Task.formatMin(s))
                        .put("intervalMin", step)
                        .put("end", Task.formatMin(e))
                )
            } else {
                o.put(
                    "times",
                    JSONArray().apply {
                        com.norbury.taktu.data.TimeSpec.timesOfDay(t.timeSpec)
                            .forEach { put(Task.formatMin(it)) }
                    }
                )
            }
        }
        if (t.restDayDeadlineMin >= 0) {
            o.put("restDayDeadline", Task.formatMin(t.restDayDeadlineMin))
        }
        o.put("urgeBefore", t.preOffsetMin)
        o.put("recheckAfter", t.postOffsetMin)
        // 择一提醒（v3.34.96）：确认任一个时刻即算当天完成；不需要就不输出
        if (t.eitherMode) o.put("either", true)
        // 逐时刻的开始 / 复查（v7，2026-10-22）：非空才导出。
        // 数组元素 {"time":"11:25","urgeBefore":30,"recheckAfter":20}；
        // 语义与顶层 urgeBefore / recheckAfter 一致（偏移分钟，-1 关闭 / 0 准时）。
        // 缺省 = 全部时刻沿用顶层的统一偏移，老版本 App 读到也能正常提醒。
        if (t.timeOffsets.isNotBlank()) {
            o.put(
                "timeOffsets",
                JSONArray().apply {
                    com.norbury.taktu.data.TimeOffsets.parse(t.timeOffsets)
                        .toSortedMap()
                        .forEach { (timeMin, off) ->
                            put(
                                JSONObject()
                                    .put("time", Task.formatMin(timeMin))
                                    .put("urgeBefore", off.first)
                                    .put("recheckAfter", off.second)
                            )
                        }
                }
            )
        }
        o.put("repeat", repeatJson(t))
        // 按星期重复的「结束日期」（如"工作日提醒到 11-07"）以前漏导出了：
        // 导出→导入 会丢掉它，AI 也读不到。按日期的任务 endDate 在 repeat 对象里，这里不重复写。
        if (t.repeatType != RepeatRule.TYPE_DATE && t.endDate.isNotBlank()) {
            o.put("endDate", t.endDate)
        }
        // 按星期任务的「开始日期」（v3.35 起永久也记起点：「从开始时间起一直重复」）：
        // 与 endDate 同层导出，导入端对称读取，round-trip 不丢起点
        if (t.repeatType == RepeatRule.TYPE_WEEKDAY && t.anchorDate.length == 10) {
            o.put("startDate", t.anchorDate)
        }
        o.put("skipHolidays", t.skipHolidays)
        if (t.tag.isNotBlank()) o.put("tag", t.tag)
        // 「中」是默认档，不写进文件（导入端缺省同为「中」，round-trip 不变）
        if (t.priority != Task.PRIORITY_MID) o.put("priority", Task.priorityLabel(t.priority))
        // 清单以数组形式导出：多组项写成对象 {"name","sets","reps"}，一次性项仍是字符串
        // （导入端两种都认，round-trip 对称）
        val specs = t.itemSpecs
        if (specs.isNotEmpty()) {
            o.put("items", JSONArray().apply {
                specs.forEach { item ->
                    if (item.sets > 1) {
                        val obj = JSONObject().put("name", item.name).put("sets", item.sets)
                        if (item.reps > 0) obj.put("reps", item.reps)
                        put(obj)
                    } else {
                        put(item.name)
                    }
                }
            })
        }
        o.put("linkClock", t.linkedToClock)
        o.put("linkCalendar", t.linkedToCalendar)
        return o
    }

    private fun repeatJson(t: Task): Any {
        if (t.repeatType == RepeatRule.TYPE_LUNAR) {
            // 农历每年：{"type":"lunar","month":1,"day":28,"endDate":"2027-03-16"}（与导入端对称）
            val p = t.anchorDate.split("-")
            val o = JSONObject()
                .put("type", "lunar")
                .put("month", p.getOrNull(0)?.toIntOrNull() ?: 1)
                .put("day", p.getOrNull(1)?.toIntOrNull() ?: 1)
                .put("leadDays", t.lunarLeadDays)
            if (t.endDate.isNotBlank()) o.put("endDate", t.endDate)
            if (t.intervalMonths > 1) o.put("intervalYears", t.intervalMonths)
            return o
        }
        if (t.repeatType == RepeatRule.TYPE_ONCE) {
            // 一次性：{"type":"once","date":"2026-10-17"}
            return JSONObject()
                .put("type", "once")
                .put("date", t.anchorDate)
        }
        if (t.repeatType == RepeatRule.TYPE_DATE) {
            val o = JSONObject()
            o.put("type", "date")
            o.put("startDate", t.anchorDate)
            o.put("intervalMonths", t.intervalMonths)
            if (t.intervalDays > 0) o.put("intervalDays", t.intervalDays)
            // 每月/每年多选日（v3.34.124）：没有多选时不写，老端按锚点日处理
            val dm = RepeatRule.dayMaskOf(t.repeatMask)
            if (dm != 0) {
                val days = org.json.JSONArray()
                RepeatRule.daysOf(dm).forEach { days.put(it) }
                o.put("daysOfMonth", days)
            }
            if (t.monthsMask != 0) {
                val months = org.json.JSONArray()
                RepeatRule.monthsOf(t.monthsMask).forEach { months.put(it) }
                o.put("monthsOfYear", months)
            }
            if (t.endDate.isNotBlank()) o.put("endDate", t.endDate)
            return o
        }
        return when (t.repeatMask) {
            RepeatRule.DAILY -> "daily"
            RepeatRule.WEEKDAYS -> "weekdays"
            RepeatRule.WEEKEND -> "weekend"
            else -> {
                // 逐位展开成星期名（导入端 ["mon","wed"] 与 [1,3] 等价，都认）
                val names = JSONArray()
                DAY_NAMES.forEachIndexed { bit, name ->
                    if (t.repeatMask and (1 shl bit) != 0) names.put(name)
                }
                names
            }
        }
    }

    /** 位序与 RepeatRule 的掩码一致：0=周日 … 6=周六 */
    private val DAY_NAMES = listOf("sun", "mon", "tue", "wed", "thu", "fri", "sat")
}
