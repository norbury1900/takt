package com.norbury.taktu.util

import com.norbury.taktu.data.RepeatRule
import com.norbury.taktu.data.Task
import org.json.JSONArray
import org.json.JSONObject

/**
 * 任务导入解析器：**容错优先**。
 *
 * 设计初衷：训练计划往往由外部（含各类 AI 助手）生成，字段命名五花八门，
 * 同一含义可能有好几种写法。所以这里不做严格 schema 校验，而是：
 *   1. 顶层支持 {"tasks": [...]} 也支持裸数组 [...]、甚至单个任务对象
 *   2. 字段名支持中英文别名（name / title / 任务 / 名称 都认）
 *   3. 时间支持 "20:00"、1200（当日分钟）、"8:00 PM"、"20点30" 等写法
 *   4. 重复支持 daily / 每天 / 工作日 / [1,3,5] / ["mon","wed"] / 127(掩码)；
 *      repeat 写成对象 {"type":"date", ...} 时启用「按日期」账期模式
 *   5. 支持 repsPerSet（每组次数，逐组打卡）、restDayDeadline（休息日截止时间）、
 *      skipHolidays（周末 + 法定假日不提醒，调休补班日照常）、priority（优先级）等较新字段
 *
 * 解析不出的部分尽量降级为合理默认值并把原因放进 warnings，
 * 让用户能在导入前一眼复核，而不是直接报错拒绝。
 */
object TaskImporter {

    /** 单次导入上限，避免误导入超大文件把任务列表刷屏 */
    const val MAX_TASKS = 50

    /**
     * 给 AI 参考的标准模板：任何生成端照这个输出即可保证零歧义。
     *
     * **tasks 是数组，可以放多条** —— 模板给了几条不同场景的示例
     * （逐组打卡的运动 / 带起止日期的学习 / 带清单的采购 /
     * 多时刻的服药 / 间隔循环的喝水），照这个样子往后加即可，
     * 单次上限 50 条。
     *
     * version 只是格式代次标记，解析器不校验它（v1 老文件同样能导入）：
     *  - v1：name / targetCount / deadline / urgeBefore / recheckAfter / repeat / link*
     *  - v2：新增 repsPerSet（每组次数）、skipHolidays（节假日跳过）、
     *         restDayDeadline（休息日截止时间）、repeat 的对象式「按日期」写法
     *  - v3：新增 priority（优先级：高 / 中 / 低，缺省「中」，也可写数字 1/2/3）
     *  - v4：新增 items（任务清单/子项，数组或换行分隔文本）——
     *        一个时间点要办一串事（采购明细、一组训练动作）时用它，
     *        **不要拆成多条任务**，否则同一时段会挂一串闹钟
     *  - v5：新增 times（每日触发时刻表，统一「响铃期间 + 方式 + 每天时刻」模型）——
     *        数组 ["08:00","17:00"] = 固定多个时刻各响一次；
     *        对象 {"start":"08:00","intervalMin":60,"end":"22:00"} = 8:00 起每 60 分响到 22:00。
     *        催促/复查对每个时刻统一生效。缺省 times = 单时刻（走 deadline），老写法完全兼容
     *  - v6：按星期任务可用顶层 startDate / endDate 圈定起止（App 内「重复时间段」入口的
     *        开始/结束日期；结束留空 = 一直重复）。农历 / 仅一次 / 自定义期间在 App 内选择，
     *        JSON 侧 repeat 语义不变。起止日期嵌在 repeat 对象里也认（v3.34.7 兜底：
     *        只认顶层时嵌套写法会静默丢掉整个期间约束，任务变成"mask 日起天天触发"）
     *  - v7：新增 timeOffsets（**逐时刻**的 urgeBefore / recheckAfter）——
     *        `[{"time":"11:25","urgeBefore":30,"recheckAfter":20}, …]`。
     *        用户 2026-10-22 要求「每个时刻分别对应自己的开始/复查时间」（多对多）；
     *        缺省 / 省略的时刻沿用顶层的 urgeBefore / recheckAfter（= 统一偏移）
     *
     * urgeBefore / recheckAfter 的取值语义（v3.26.0 起，改前 0 = 关闭）：
     *  - **-1 = 关闭**（不催促 / 不复查）
     *  - **0 = 准时**：与截止时间同时响（截止 9:00 就 9:00 响）
     *  - **>0 = 提前 / 延后的分钟数**
     *  两个都是 -1 时这条任务没有任何响铃（导入预览会给出警告）。
     *
     * v3.34.17 起 App 内「开始时间 / 截止时间」改用**绝对钟点**编辑
     *（开始 ≤ 目标时刻，截止 ≥ 目标时刻），但**存储与本契约仍用偏移量**：
     *   开始钟点 = 目标时刻 - urgeBefore；截止钟点 = 目标时刻 + recheckAfter。
     * 也就是说 urgeBefore=90 + deadline="08:30" 对应 App 里的「开始 07:00」，
     * 导入导出完全不用改。timeOffsets 同理（每个时刻各自换算）。
     */
    const val TEMPLATE = """{
  "version": 7,
  "tasks": [
    {
      "name": "单杠抬腿",
      "targetCount": 60,
      "repsPerSet": 10,
      "deadline": "20:00",
      "urgeBefore": 30,
      "recheckAfter": 120,
      "repeat": "daily",
      "skipHolidays": true,
      "tag": "运动",
      "priority": "高",
      "linkClock": true,
      "linkCalendar": false
    },
    {
      "name": "背单词",
      "targetCount": 30,
      "deadline": "21:30",
      "urgeBefore": 15,
      "recheckAfter": 60,
      "repeat": "weekdays",
      "startDate": "2026-10-20",
      "endDate": "2026-12-31",
      "skipHolidays": true,
      "tag": "学习",
      "priority": "低",
      "linkClock": false,
      "linkCalendar": false
    },
    {
      "name": "每周采购",
      "deadline": "10:00",
      "urgeBefore": 30,
      "recheckAfter": -1,
      "repeat": ["sat"],
      "tag": "生活",
      "priority": "中",
      "items": ["牛奶 2L", "鸡蛋 30 个", "鸡胸肉 1kg"],
      "linkClock": false,
      "linkCalendar": false
    },
    {
      "name": "服药提醒",
      "times": ["08:00", "12:30", "19:00"],
      "urgeBefore": 10,
      "repeat": "daily",
      "tag": "健康",
      "priority": "高",
      "linkClock": false,
      "linkCalendar": false
    },
    {
      "name": "喝水提醒",
      "times": { "start": "09:00", "intervalMin": 120, "end": "21:00" },
      "repeat": "daily",
      "tag": "健康",
      "priority": "低",
      "linkClock": false,
      "linkCalendar": false
    },
    {
      "name": "练字",
      "times": ["09:00", "21:00"],
      "urgeBefore": 15,
      "recheckAfter": 60,
      "timeOffsets": [
        { "time": "09:00", "urgeBefore": 30, "recheckAfter": 0 },
        { "time": "21:00", "urgeBefore": -1, "recheckAfter": -1 }
      ],
      "repeat": "daily",
      "tag": "学习",
      "priority": "中",
      "linkClock": false,
      "linkCalendar": false
    },
    {
      "name": "给导师回邮件",
      "deadline": "14:00",
      "urgeBefore": 30,
      "recheckAfter": 60,
      "repeat": "once",
      "date": "2026-10-28",
      "tag": "工作",
      "priority": "高",
      "linkClock": false,
      "linkCalendar": true
    }
  ]
}"""

    /**
     * 联动开关在 JSON 里的"是否显式给出"。
     *
     * 存在的意义：合并「改这条任务」时，只有 JSON **显式写了** linkClock / linkCalendar
     * 才用 JSON 的值，没写才保留库里的旧值。
     * 之前合并用 OR（`新 || 旧`），导致**一旦打开就再也关不掉**——
     * 远端把 linkClock 改成 false 会被旧值 true 吞掉。
     */
    data class LinkSpec(
        val clockSpecified: Boolean,
        val clock: Boolean,
        val calendarSpecified: Boolean,
        val calendar: Boolean
    )

    data class Result(
        val tasks: List<Task>,
        val warnings: List<String>,
        val error: String?,
        /**
         * 删除请求（远端编辑用）：只写了 `{"id": 12, "delete": true}` 的条目。
         * 和 [tasks] 里的 id > 0 一起构成本次操作：id 有值且库里存在 = 改，否则 = 新增。
         */
        val deletes: List<Long> = emptyList(),
        /**
         * 与 [tasks] **按下标对齐**：每条的联动开关是否被 JSON 显式指定。
         * 下标可能缺失（老调用方），取不到时按"未指定"处理。
         */
        val linkSpecs: List<LinkSpec?> = emptyList()
    ) {
        val ok: Boolean get() = error == null
    }

    /* ---------------- 解析入口 ---------------- */

    fun parse(text: String): Result {
        val warnings = mutableListOf<String>()
        val raw = text.trim()
        if (raw.isEmpty()) return Result(emptyList(), emptyList(), "内容为空")

        val arr: JSONArray = try {
            if (raw.startsWith("[")) {
                JSONArray(raw)
            } else {
                val obj = JSONObject(raw)
                when {
                    obj.has("tasks") -> obj.getJSONArray("tasks")
                    obj.has("items") -> obj.getJSONArray("items")
                    obj.has("plans") -> obj.getJSONArray("plans")
                    obj.has("plan") -> obj.getJSONArray("plan")
                    else -> JSONArray().put(obj)   // 单个任务对象也认
                }
            }
        } catch (e: Exception) {
            return Result(emptyList(), emptyList(), "无法解析：${e.message ?: "不是合法的 JSON"}")
        }

        val out = ArrayList<Task>()
        val specs = ArrayList<LinkSpec?>()
        val deletes = ArrayList<Long>()
        val now = System.currentTimeMillis()

        for (i in 0 until arr.length()) {
            if (out.size >= MAX_TASKS) {
                warnings.add("任务数量超过上限 $MAX_TASKS 条，多余部分已忽略")
                break
            }
            val node = arr.opt(i) as? JSONObject ?: continue
            // 删除请求：只带 id + delete，没有任务字段
            if (pickBool(node, "delete", "remove", "删除") == true) {
                val id = pickLong(node, "id", "taskId", "任务id", "编号")
                if (id != null && id > 0) deletes.add(id)
                else warnings.add("第 ${i + 1} 项：删除请求缺少 id，已忽略")
                continue
            }
            parseTask(node, i, warnings, now, out.size)?.let { t ->
                out.add(t)
                // 与 out 严格对齐：这条任务解析成功才记它的联动规格
                specs.add(linkSpecOf(node))
            }
        }

        return if (out.isEmpty() && deletes.isEmpty()) {
            val err = if (warnings.isEmpty()) "没有解析到任何任务，请检查是否含有 name/名称 字段" else null
            Result(emptyList(), warnings, err)
        } else {
            Result(out, warnings, null, deletes, specs)
        }
    }

    /**
     * 读出这条 JSON 里联动开关的"是否显式给出 + 给的值"。
     *
     * 关键：区分「没写这个字段」和「写了 false」——
     * 前者保留库里旧值，后者用 JSON 的值（否则一旦打开就再也关不掉）。
     */
    private fun linkSpecOf(o: JSONObject): LinkSpec {
        val clockRaw = pickRaw(o, "linkClock", "syncClock", "systemAlarm", "clock", "小米闹钟")
        val calRaw = pickRaw(o, "linkCalendar", "syncCalendar", "calendar", "日历")
        return LinkSpec(
            clockSpecified = clockRaw != null,
            clock = pickBool(o, "linkClock", "syncClock", "systemAlarm", "clock", "小米闹钟") ?: false,
            calendarSpecified = calRaw != null,
            calendar = pickBool(o, "linkCalendar", "syncCalendar", "calendar", "日历") ?: false
        )
    }

    /* ---------------- 单条任务 ---------------- */

    private fun parseTask(
        o: JSONObject,
        index: Int,
        warnings: MutableList<String>,
        now: Long,
        order: Int
    ): Task? {
        val label = "第 ${index + 1} 项"

        val name = pickStr(
            o, "name", "title", "task", "taskName", "标题", "任务", "名称", "动作",
            "exercise", "training", "planName"
        )
        if (name.isNullOrBlank()) {
            warnings.add("$label：缺少任务名称，已跳过")
            return null
        }

        val deadline = pickMinute(
            o, "deadline", "deadlineMin", "time", "due", "dueTime", "targetTime",
            "at", "目标", "目标时间", "时间", "完成时间", "提醒时间"
        ) ?: (20 * 60).also { warnings.add("$label：未指定目标时间，默认 20:00") }

        // 法定休息日的截止时间；不填就与主时间相同（-1）
        val restDayDeadline = pickMinute(
            o, "restDayDeadline", "restDayDeadlineMin", "restDayTime", "holidayDeadline",
            "holidayTime", "holidayDue", "休息日时间", "节假日时间", "法定休息日时间"
        )

        val pre = pickInt(
            o, "urgeBefore", "preOffset", "preOffsetMin", "urge", "remindBefore",
            "notifyBefore", "催促", "提前", "提醒提前"
        ) ?: 30

        val post = pickInt(
            o, "recheckAfter", "postOffset", "postOffsetMin", "recheck",
            "复查", "复检", "延后", "截止", "截止时间"
        ) ?: 120

        val target = pickInt(
            o, "targetCount", "target", "count", "reps", "targetReps",
            "目标", "次数", "数量"
        ) ?: 0

        // 每组次数（逐组打卡）：目标 60 次、每组 10 → 卡片上记 6 组，做满自动算完成
        val perSet = pickInt(
            o, "repsPerSet", "setSize", "setReps", "perSet", "groupCount",
            "每组", "每组次数", "单组次数", "单组"
        ) ?: 0

        val repeat = pickRepeat(
            o, "repeat", "repeatMask", "days", "schedule", "weekdays",
            "重复", "周期", "提醒日"
        ) ?: RepeatRule.DAILY

        // 每日触发时刻表（v5）：数组 = 固定多个时刻，对象 = 间隔循环。缺省走单时刻 deadline
        val timeSpec = pickTimeSpec(o)

        // 节假日跳过：周末 + 法定假日不提醒（调休补班日照常）。缺省跟随新建任务的默认值
        val skipHolidays = pickBool(
            o, "skipHolidays", "skipRestDay", "skipRest", "skipWeekend",
            "skipHoliday", "跳过节假日", "节假日跳过", "跳过周末", "跳过休息日"
        ) ?: true

        // 日期型重复：repeat 写成对象时启用（房租这类"每 N 个月的某号 + 结束日期"）
        val dateRule = pickDateRule(o)

        // 农历每年：{"type":"lunar","month":1,"day":28,"leadDays":1}（生日 / 传统节日）
        val lunarRule = pickLunarRule(o)

        // 一次性：repeat 写 "once"（配顶层 date/startDate），或写成 {"type":"once","date":"2026-10-17"}
        val onceDate = pickOnceDate(o)

        // repeat 对象本体：按星期任务的起止日期可能嵌在这里（见下方 anchorDate/endDate 兜底）
        val repeatObj = pickRaw(o, "repeat", "repeatRule", "schedule", "重复", "周期") as? JSONObject

        val linkClock = pickBool(
            o, "linkClock", "syncClock", "systemAlarm", "clock", "小米闹钟"
        ) ?: false

        val linkCalendar = pickBool(
            o, "linkCalendar", "syncCalendar", "calendar", "日历"
        ) ?: false

        val tag = pickStr(
            o, "tag", "category", "group", "分类", "标签", "分组"
        ) ?: ""

        return Task(
            // id > 0 = 「改这条」；文件导入端会把 id 清零当新增处理，只有远端编辑才用得到
            id = pickLong(o, "id", "taskId", "任务id", "编号") ?: 0L,
            name = name,
            items = pickItems(o),
            targetCount = target,
            doneCount = 0,
            // 时刻表生效时截止取第一个时刻：卡片/响铃页显示用，真触发以时刻表为准
            deadlineMin = if (timeSpec.isNotBlank()) {
                com.norbury.taktu.data.TimeSpec.timesOfDay(timeSpec).firstOrNull() ?: deadline
            } else deadline,
            restDayDeadlineMin = restDayDeadline ?: -1,
            preOffsetMin = pre.coerceIn(Task.OFFSET_OFF, 24 * 60),
            postOffsetMin = post.coerceIn(Task.OFFSET_OFF, 24 * 60),
            repsPerSet = perSet.coerceIn(0, maxOf(target, 1)),
            skipHolidays = skipHolidays,
            // 注意：Kotlin 的 infix 优先级低于 ==，必须显式加括号
            repeatMask = if (dateRule != null && dateRule.mask != 0) dateRule.mask
            else run {
                val m = repeat and 0b1111111
                // 掩码为 0 表示"没有任何重复日"，会导致永不提醒，降级为仅今天
                if (m == 0) RepeatRule.todayMask() else m
            },
            tag = tag,
            priority = parsePriority(o),
            repeatType = when {
                onceDate != null -> RepeatRule.TYPE_ONCE
                dateRule != null -> RepeatRule.TYPE_DATE
                lunarRule != null -> RepeatRule.TYPE_LUNAR
                else -> RepeatRule.TYPE_WEEKDAY
            },
            anchorDate = onceDate ?: dateRule?.anchor
                    ?: lunarRule?.let { "%02d-%02d".format(it.month, it.day) }
                    // 按星期任务的开始日期（v3.35 起永久也记起点；与导出端顶层 startDate 对称）
                    ?: dateFrom(o, "startDate", "anchorDate", "开始日期", "起始日期")
                    // 兜底：AI 写的 JSON 常把起止日期嵌在 repeat 对象里
                    // （{"type":"weekday","startDate":"2026-10-19","endDate":"2026-10-21"}），
                    // 只认顶层会静默丢掉整个期间约束——任务变成"mask 日起天天触发"，
                    // 计划页/统计里会出现在建档日之前的某天（v3.34.7 用户实测 bug）
                    ?: repeatObj?.let {
                        dateFrom(it, "startDate", "anchorDate", "start", "首次日期", "开始日期", "起始日期")
                    }
                    ?: "",
            lunarLeadDays = lunarRule?.leadDays ?: 0,
            intervalMonths = dateRule?.intervalMonths ?: lunarRule?.intervalYears ?: 1,
            intervalDays = dateRule?.intervalDays ?: 0,
            monthsMask = dateRule?.monthsMask ?: 0,
            // 按日期/农历模式的 endDate 从 repeat 对象里取；按星期模式认顶层 endDate（如「工作日提醒到 11-07」），
            // 再兜底认 repeat 对象里嵌的 endDate
            endDate = dateRule?.endDate
                ?: lunarRule?.endDate
                ?: pickStr(o, "endDate", "until", "untilDate", "结束日期", "截止日期")
                ?: repeatObj?.let {
                    pickStr(it, "endDate", "until", "untilDate", "结束日期", "截止日期")
                }
                ?: "",
            enabled = true,
            sortOrder = (now + order).toInt(),
            createdAt = now,
            linkedToClock = linkClock,
            linkedToCalendar = linkCalendar,
            timeSpec = timeSpec,
            // 逐时刻的始 / 止（v7）：认不出来就是空串 = 全部沿用顶层的统一偏移
            timeOffsets = pickTimeOffsets(o, timeSpec),
            // 择一提醒（v3.34.96）：确认任一个时刻即算当天完成
            eitherMode = pickBool(o, "either", "eitherMode", "择一", "任选") ?: false
        )
    }

    /**
     * 每日触发时刻表解析：
     *  - 数组 ["08:00","17:00"] / [480, 1020]（也认分钟数）→ 固定时刻列表
     *  - 对象 {"start":"08:00","intervalMin":60,"end":"22:00"} → 间隔循环
     * 认不出返回空串（走单时刻模式）。deadline 同步取第一个时刻，卡片显示不分裂。
     */
    private fun pickTimeSpec(o: JSONObject): String {
        val raw = pickRaw(o, "times", "timeSpec", "timesOfDay", "时刻", "响铃时刻", "每天时刻")
            ?: return ""
        return when (raw) {
            is JSONArray -> {
                val mins = (0 until raw.length()).mapNotNull { i ->
                    when (val e = raw.opt(i)) {
                        is Number -> dayMin(e.toInt())
                        is String -> minuteFromText(e)
                        else -> null
                    }
                }
                com.norbury.taktu.data.TimeSpec.encodeFixed(mins)
            }
            is JSONObject -> {
                val start = pickMinute(raw, "start", "startTime", "from", "开始", "开始时刻") ?: return ""
                val step = pickInt(
                    raw, "intervalMin", "interval", "every", "everyMin", "step", "间隔", "每隔", "间隔分钟"
                ) ?: return ""
                val end = pickMinute(raw, "end", "endTime", "until", "结束", "结束时刻", "响到")
                    ?: return ""
                com.norbury.taktu.data.TimeSpec.encodeInterval(start, step, end)
            }
            else -> ""
        }
    }

    /**
     * 逐时刻的开始 / 复查（v7）：`[{"time":"11:25","urgeBefore":30,"recheckAfter":20}, …]`
     * → 内部串 `"825:30/20"`。缺省 / 认不出返回空串 = 全部时刻沿用顶层统一偏移。
     * 只收落在时刻表里的项，其余忽略（导入方的时刻表才是准的）。
     */
    private fun pickTimeOffsets(o: JSONObject, timeSpec: String): String {
        val raw = pickRaw(o, "timeOffsets", "逐时刻提醒", "各时刻提醒") ?: return ""
        if (raw !is JSONArray) return ""
        val map = LinkedHashMap<Int, Pair<Int, Int>>()
        (0 until raw.length()).forEach { i ->
            val e = raw.opt(i) as? JSONObject ?: return@forEach
            val timeMin = pickMinute(e, "time", "at", "时刻", "时间") ?: return@forEach
            val pre = pickInt(e, "urgeBefore", "preOffset", "urge", "提前", "开始") ?: return@forEach
            val post = pickInt(e, "recheckAfter", "postOffset", "recheck", "延后", "复查") ?: return@forEach
            map[timeMin] = pre to post
        }
        if (map.isEmpty()) return ""
        val retained = com.norbury.taktu.data.TimeOffsets.retain(
            com.norbury.taktu.data.TimeOffsets.encode(map),
            com.norbury.taktu.data.TimeSpec.timesOfDay(timeSpec)
        )
        return retained
    }

    /** "8:00" / "20点30" / "1200" → 当日分钟数（数组元素没有键名，单独解析） */
    private fun minuteFromText(s: String): Int? {
        val t = s.trim().lowercase()
        t.toIntOrNull()?.let { return dayMin(it) }
        val m = Regex("(\\d{1,2})\\s*[:：点.]\\s*(\\d{1,2})").find(t) ?: return null
        return dayMin(m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt())
    }

    /* ---------------- 取值工具（别名匹配） ---------------- */

    /**
     * 一次性任务（购物、办证件这类"只响一次"）：认三种写法
     *  1. `"repeat": {"type":"once", "date":"2026-10-17"}`
     *  2. `"repeat": "once"` + 顶层 `date` / `startDate`
     *  3. 顶层 `onceDate`
     * 日期一律 yyyy-MM-dd；认不出就返回 null（按普通重复处理）。
     */
    private fun pickOnceDate(o: JSONObject): String? {
        val dateKeys = arrayOf("date", "startDate", "anchorDate", "日期", "开始日期")
        when (val r = pickRaw(o, "repeat", "repeatRule", "重复")) {
            is JSONObject -> {
                val type = (r.optString("type").ifBlank { r.optString("rule") }).lowercase()
                return if (type.contains("once") || type.contains("一次性") || type.contains("单次")) {
                    dateFrom(r, *dateKeys) ?: dateFrom(o, *dateKeys)
                } else {
                    null
                }
            }
            is String -> {
                val s = r.trim().lowercase()
                return if (s == "once" || s.contains("一次性") || s.contains("仅一次") || s.contains("单次")) {
                    dateFrom(o, *dateKeys) ?: dateFrom(o, "onceDate", "单次日期")
                } else {
                    null
                }
            }
            else -> Unit
        }
        return dateFrom(o, "onceDate", "单次日期", "一次性日期")
    }

    /** 从对象里取一个 yyyy-MM-dd 日期（容忍 ISO 带时间的形式） */
    private fun dateFrom(o: JSONObject, vararg keys: String): String? {
        val s = pickStr(o, *keys) ?: return null
        return Regex("\\d{4}-\\d{2}-\\d{2}").find(s)?.value
    }

    /** 归一化键名：转小写并去掉标点空白，中文字符天然属于 letter 不会被剔除 */
    private fun norm(k: String): String = k.lowercase().filter { it.isLetterOrDigit() }

    private fun pickKey(o: JSONObject, keys: Array<out String>): String? {
        val wanted = keys.map { norm(it) }.toSet()
        for (k in o.keys()) if (norm(k) in wanted) return k
        return null
    }

    private fun pickRaw(o: JSONObject, vararg keys: String): Any? {
        val k = pickKey(o, keys) ?: return null
        val v = o.opt(k)
        return if (v === JSONObject.NULL) null else v
    }

    /**
     * 优先级：0=高 1=中 2=低。
     * 认「高 / 中 / 低」「high / medium / low」，也认数字 1/2/3（1 = 最高，符合直觉）；
     * 认不出来就落到「中」—— 不因为一个可选字段把整条任务判失败。
     */
    private fun parsePriority(o: JSONObject): Int =
        when (val v = pickRaw(o, "priority", "优先级", "重要程度", "级别", "重要度")) {
            null -> Task.PRIORITY_MID
            is Number -> when (v.toInt()) {
                1 -> Task.PRIORITY_HIGH
                3 -> Task.PRIORITY_LOW
                else -> Task.PRIORITY_MID
            }
            else -> when (v.toString().trim().lowercase()) {
                "高", "重要", "紧急", "高优先", "high", "h", "urgent", "1" -> Task.PRIORITY_HIGH
                "低", "次要", "不着急", "low", "l", "3" -> Task.PRIORITY_LOW
                else -> Task.PRIORITY_MID
            }
        }

    /**
     * 任务清单（子项）：认数组 ["牛奶 ×2","鸡胸肉"]，也认换行 / 顿号 / 分号分隔的一整段文本。
     * 存进库时统一成换行分隔（见 Task.items）。
     */
    private fun pickItems(o: JSONObject): String {
        val raw = pickRaw(o, "items", "item", "list", "checklist", "subtasks", "清单", "明细", "子项")
            ?: return ""
        val parts = when (raw) {
            is JSONArray -> (0 until raw.length()).mapNotNull { i ->
                when (val el = raw.opt(i)) {
                    // 结构化子项：{"name":"哑铃卧推","sets":4,"reps":8}
                    // → 存成文本约定「名字|4|8」（组数由导入时显式给出，不做文本猜测）
                    is JSONObject -> {
                        val name = pickStr(el, "name", "title", "item", "动作", "名称")
                            ?: return@mapNotNull null
                        val sets = pickInt(el, "sets", "组数", "组") ?: 1
                        val reps = pickInt(el, "reps", "每组", "次数", "每组次数") ?: 0
                        when {
                            sets > 1 && reps > 0 -> "$name|$sets|$reps"
                            sets > 1 -> "$name|$sets"
                            else -> name
                        }
                    }
                    else -> el?.toString()?.takeIf { it.isNotBlank() }
                }
            }
            else -> raw.toString().split('\n', '、', ';', '；')
        }
        return parts.map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
    }

    private fun pickLong(o: JSONObject, vararg keys: String): Long? =
        when (val v = pickRaw(o, *keys)) {
            is Number -> v.toLong()
            is String -> v.filter { it.isDigit() }.toLongOrNull()
            else -> null
        }

    private fun pickStr(o: JSONObject, vararg keys: String): String? =
        (pickRaw(o, *keys) as? String)?.trim()?.takeIf { it.isNotBlank() }

    private fun pickInt(o: JSONObject, vararg keys: String): Int? {
        val v = pickRaw(o, *keys) ?: return null
        return when (v) {
            is Int -> v
            is Number -> v.toInt()
            // 允许负数（-1 = 关闭催促/复查），正数原有的行为不变
            is String -> Regex("-?[0-9]+").find(v)?.value?.toIntOrNull()
            else -> null
        }
    }

    private fun pickBool(o: JSONObject, vararg keys: String): Boolean? =
        when (val v = pickRaw(o, *keys)) {
            null -> null
            is Boolean -> v
            is Number -> v.toInt() != 0
            is String -> {
                val s = v.trim().lowercase()
                when (s) {
                    "true", "yes", "y", "1", "on", "是", "开", "开启" -> true
                    "false", "no", "n", "0", "off", "否", "关", "关闭" -> false
                    else -> null
                }
            }
            else -> null
        }

    /**
     * 日期型重复解析。支持：
     *   "repeat": { "type": "date", "startDate": "2026-07-21",
     *               "intervalMonths": 3, "endDate": "2027-07-21" }
     * 按天间隔："intervalDays": 7（此时忽略 intervalMonths）。
     * 也接受 type=monthly + dayOfMonth（没有 startDate 时锚定到最近的该号）。
     */
    private fun pickDateRule(o: JSONObject): DateRule? {
        val v = pickRaw(o, "repeat", "schedule", "重复", "周期") as? JSONObject ?: return null
        val type = (pickStr(v, "type", "模式", "类型") ?: "").lowercase()
        val yearly = type in setOf("yearly", "annual", "每年", "按年")
        if (type !in setOf(
                "date", "monthly", "dayofmonth", "按日期", "日期", "按月", "每月",
                "yearly", "annual", "每年", "按年"
            )
        ) return null

        var anchor = pickStr(
            v, "startDate", "anchorDate", "start", "首次日期", "开始日期", "起始日期"
        ) ?: ""
        val interval = if (yearly) {
            (pickInt(v, "intervalMonths", "interval", "months", "间隔月", "间隔") ?: 12)
                .coerceAtLeast(1)
        } else {
            (pickInt(v, "intervalMonths", "interval", "months", "间隔月", "间隔") ?: 1)
                .coerceAtLeast(1)
        }
        val intervalDays = pickInt(v, "intervalDays", "interval_days", "days", "间隔天", "间隔天数") ?: 0
        val end = pickStr(v, "endDate", "until", "untilDate", "结束日期", "截止日期") ?: ""

        // 多选日（每月/每年的「几号」）：daysOfMonth / monthDays / 几号
        val daysRaw = v.opt("daysOfMonth") ?: v.opt("monthDays") ?: v.opt("几号")
        val days: List<Int> = when (daysRaw) {
            is JSONArray -> (0 until daysRaw.length()).mapNotNull { i ->
                daysRaw.optInt(i, 0).takeIf { it in 1..31 }
            }
            is Number -> listOf(daysRaw.toInt()).filter { it in 1..31 }
            is String -> daysRaw.split(',', '，', ' ')
                .mapNotNull { it.trim().toIntOrNull()?.takeIf { d -> d in 1..31 } }
            else -> emptyList()
        }
        // 每年只给月份 = 整月提醒（v3.34.126）
        val dayMask = if (yearly && days.isEmpty()) RepeatRule.FULL_MONTH_MASK
        else RepeatRule.maskOfDays(days)

        val monthsRaw = v.opt("monthsOfYear") ?: v.opt("months") ?: v.opt("几月")
        val months: List<Int> = when (monthsRaw) {
            is JSONArray -> (0 until monthsRaw.length()).mapNotNull { i ->
                monthsRaw.optInt(i, 0).takeIf { it in 1..12 }
            }
            is Number -> listOf(monthsRaw.toInt()).filter { it in 1..12 }
            is String -> monthsRaw.split(',', '，', ' ')
                .mapNotNull { it.trim().toIntOrNull()?.takeIf { m -> m in 1..12 } }
            else -> emptyList()
        }
        val monthMask = RepeatRule.maskOfMonths(months)

        if (anchor.isBlank()) {
            val day = pickInt(v, "dayOfMonth", "day", "几号", "日")
                ?: days.firstOrNull()
                ?: if (yearly) 1 else return null
            if (day !in 1..31) return null
            anchor = if (yearly) {
                val month = pickInt(v, "month", "月") ?: 1
                nextAnchorForMonthDay(month, day)
            } else {
                nextAnchorForDay(day)
            }
        }
        if (anchor.isBlank()) return null
        return DateRule(anchor, interval, end, intervalDays.coerceAtLeast(0), dayMask, monthMask)
    }

    /** 每年任务只给了月/日时，找下一个（含今天）命中的日期 */
    private fun nextAnchorForMonthDay(month: Int, day: Int): String {
        val cal = java.util.Calendar.getInstance()
        repeat(800) {
            val m = cal.get(java.util.Calendar.MONTH) + 1
            val dom = cal.get(java.util.Calendar.DAY_OF_MONTH)
            val dim = cal.getActualMaximum(java.util.Calendar.DAY_OF_MONTH)
            if (m == month && (dom == day || (day > dim && dom == dim))) {
                return "%04d-%02d-%02d".format(cal.get(java.util.Calendar.YEAR), m, dom)
            }
            cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
        }
        return ""
    }

    /** pickDateRule 的结果：(锚点日, 间隔月, 结束日, 间隔天；intervalDays>0 优先按天, 多选日掩码) */
    private data class DateRule(
        val anchor: String,
        val intervalMonths: Int,
        val endDate: String,
        val intervalDays: Int,
        val mask: Int = 0,
        val monthsMask: Int = 0,
    )

    /**
     * 农历每年任务（生日 / 传统节日）：{"type":"lunar","month":1,"day":28,"endDate":"2027-03-16"}。
     * month 1–12（正月=1），day 1–30，leadDays 0=当天、1=前一天；认不出返回 null。
     */
    private fun pickLunarRule(o: JSONObject): LunarRule? {
        val v = pickRaw(o, "repeat", "schedule", "重复", "周期") as? JSONObject ?: return null
        val type = (pickStr(v, "type", "模式", "类型") ?: "").lowercase()
        if (!type.contains("lunar") && !type.contains("农历")) return null
        val m = pickInt(v, "month", "lunarMonth", "农历月", "月") ?: 0
        val d = pickInt(v, "day", "lunarDay", "农历日", "日") ?: 0
        val lead = pickInt(v, "leadDays", "lead", "提前天数", "提前") ?: 0
        val end = pickStr(v, "endDate", "until", "结束日期", "截止日期") ?: ""
        val every = pickInt(v, "intervalYears", "间隔年") ?: 1
        if (m !in 1..12 || d !in 1..30) return null
        return LunarRule(m, d, lead.coerceIn(0, 30), end, every.coerceAtLeast(1))
    }

    /** pickLunarRule 的结果：(农历月, 农历日, 提前几天, 结束日期, 间隔年数) */
    private data class LunarRule(
        val month: Int,
        val day: Int,
        val leadDays: Int,
        val endDate: String,
        val intervalYears: Int,
    )

    /** 未来的第一个「day 号」（含今天），作为锚点 */
    private fun nextAnchorForDay(day: Int): String {
        val cal = java.util.Calendar.getInstance()
        return (0..62).firstNotNullOfOrNull { i ->
            val c = (cal.clone() as java.util.Calendar).apply {
                add(java.util.Calendar.DAY_OF_YEAR, i)
            }
            if (c.get(java.util.Calendar.DAY_OF_MONTH) == day) {
                "%04d-%02d-%02d".format(
                    c.get(java.util.Calendar.YEAR),
                    c.get(java.util.Calendar.MONTH) + 1,
                    c.get(java.util.Calendar.DAY_OF_MONTH)
                )
            } else null
        } ?: ""
    }

    /* ---------------- 时间解析 ---------------- */

    private fun pickMinute(o: JSONObject, vararg keys: String): Int? {
        val v = pickRaw(o, *keys) ?: return null
        return when (v) {
            is Number -> dayMin(v.toInt())
            is String -> {
                val s = v.trim().lowercase()
                // 20:00 / 20：30 / 20点30 / 20.30
                val m = Regex("(\\d{1,2})\\s*[:：点.]\\s*(\\d{1,2})").find(s)
                if (m != null) {
                    var h = m.groupValues[1].toInt()
                    val mi = m.groupValues[2].toInt()
                    if (s.contains("pm") && h < 12) h += 12
                    if (s.contains("am") && h == 12) h = 0
                    dayMin(h * 60 + mi)
                } else {
                    // 纯数字按"当日分钟数"处理
                    Regex("[0-9]+").find(s)?.value?.toIntOrNull()?.let { dayMin(it) }
                }
            }
            else -> null
        }
    }

    private fun dayMin(m: Int): Int = ((m % 1440) + 1440) % 1440

    /* ---------------- 重复规则解析 ---------------- */

    private fun pickRepeat(o: JSONObject, vararg keys: String): Int? {
        val v = pickRaw(o, *keys) ?: return null
        return when (v) {
            is Number -> if (v.toInt() in 0..127) v.toInt() else null   // 直接当 7 位掩码
            is String -> {
                val s = v.trim().lowercase().replace("_", "").replace("-", "").replace(" ", "")
                when (s) {
                    "daily", "everyday", "alldays", "每天", "每日", "天天" -> RepeatRule.DAILY
                    "weekdays", "工作日", "周中" -> RepeatRule.WEEKDAYS
                    "weekend", "weekends", "周末" -> RepeatRule.WEEKEND
                    "none", "once", "单次", "仅今天", "不重复" -> RepeatRule.todayMask()
                    else -> dayBit(s)?.let { 1 shl it }
                }
            }
            is JSONArray -> maskOf(v)
            // 对象式按星期：{"type":"weekday","days":["mon","wed"],"startDate":...}
            // （起止日期在 Task 构造处兜底认；这里只取星期掩码——不认会整条回落成「每天」）
            is JSONObject ->
                (pickRaw(v, "days", "weekdays", "repeatDays", "星期", "提醒日") as? JSONArray)
                    ?.let { maskOf(it) }
            else -> null
        }
    }

    private fun maskOf(arr: JSONArray): Int? {
        var mask = 0
        val nums = ArrayList<Int>()
        for (i in 0 until arr.length()) {
            when (val e = arr.opt(i)) {
                is Number -> nums.add(e.toInt())
                is String -> dayBit(e)?.let { mask = mask or (1 shl it) }
            }
        }
        if (nums.isNotEmpty()) {
            val max = nums.maxOrNull() ?: 0
            for (n in nums) {
                // 含 0 或最大值<=6 → 按「0=周日」体系；否则按 ISO（1=周一…7=周日）
                val bit = if (n == 0 || max <= 6) n.coerceIn(0, 6)
                else if (n == 7) 0 else n.coerceIn(1, 7)
                mask = mask or (1 shl bit)
            }
        }
        return if (mask == 0) null else mask
    }

    private val DAY_BITS = mapOf(
        "sun" to 0, "sunday" to 0, "周日" to 0, "日" to 0, "星期天" to 0, "周天" to 0, "天" to 0,
        "mon" to 1, "monday" to 1, "周一" to 1, "一" to 1,
        "tue" to 2, "tues" to 2, "tuesday" to 2, "周二" to 2, "二" to 2,
        "wed" to 3, "wednesday" to 3, "周三" to 3, "三" to 3,
        "thu" to 4, "thur" to 4, "thurs" to 4, "thursday" to 4, "周四" to 4, "四" to 4,
        "fri" to 5, "friday" to 5, "周五" to 5, "五" to 5,
        "sat" to 6, "saturday" to 6, "周六" to 6, "六" to 6
    )

    private fun dayBit(s: String): Int? {
        val t = s.trim().lowercase()
            .replace("星期", "").replace("礼拜", "").replace("周", "")
            .replace("_", "").replace(" ", "")
            .trim()
        return DAY_BITS[t]
    }
}
