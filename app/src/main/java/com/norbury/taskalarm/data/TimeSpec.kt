package com.norbury.taskalarm.data

/**
 * 每日触发时刻表（Room v14 新增，Task.timeSpec 的解析层）。
 *
 * 统一的「响铃期间 + 期间内方式 + 每天触发时刻」模型里，时刻表负责最后一段：
 * 同一天内什么时候响。两种格式：
 *  - **固定列表**：`"480,1020"` → 8:00 和 17:00 各响一次（逗号分隔的当日分钟数）
 *  - **间隔循环**：`"480/60/1320"` → 8:00 起每隔 60 分钟响到 22:00（开始/间隔/结束）
 *
 * 空串 = 旧单时刻模式（走 deadlineMin），[Task.timesOn] 里分流。
 * 全部纯函数，单测覆盖（TimeSpecTest）。
 */
object TimeSpec {

    /** 一天内最多时刻数：间隔选 1 分钟这类密集档时会被截到这里，防止把系统闹钟排爆 */
    const val MAX_PER_DAY = 48

    /**
     * 间隔循环的最小间隔（分钟）。用户 2026-10-20 要求间隔滚轮覆盖
     * 「一天内每隔多少分钟」1 分钟 ~ 24 小时（1440 分钟），引擎层同步放开到 1。
     */
    const val MIN_INTERVAL = 1

    /** 是否间隔循环格式 */
    fun isInterval(spec: String): Boolean = spec.contains('/')

    /**
     * 固定列表解析：`"480,1020"` → [480, 1020]（升序、去重、归一到当日）。
     * 非法条目跳过；全部非法返回空列表。
     */
    fun parseFixed(spec: String): List<Int> =
        spec.split(',')
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it in 0 until 1440 }
            .distinct()
            .sorted()

    /** 间隔循环解析：`"480/60/1320"` → (开始, 间隔, 结束)；非法返回 null */
    fun parseInterval(spec: String): Triple<Int, Int, Int>? {
        val p = spec.split('/')
        val start = p.getOrNull(0)?.trim()?.toIntOrNull() ?: return null
        val step = p.getOrNull(1)?.trim()?.toIntOrNull() ?: return null
        val end = p.getOrNull(2)?.trim()?.toIntOrNull() ?: return null
        if (start !in 0 until 1440 || end !in 0 until 1440) return null
        if (step < MIN_INTERVAL) return null
        if (end < start) return null
        return Triple(start, step, end)
    }

    /** 展开成当日时刻列表（升序、去重、封顶 [MAX_PER_DAY]）；空/非法返回空列表 */
    fun timesOfDay(spec: String): List<Int> {
        if (spec.isBlank()) return emptyList()
        return if (isInterval(spec)) {
            val (start, step, end) = parseInterval(spec) ?: return emptyList()
            val out = ArrayList<Int>()
            var t = start
            while (t <= end && out.size < MAX_PER_DAY) {
                out.add(t)
                t += step
            }
            out
        } else {
            parseFixed(spec).take(MAX_PER_DAY)
        }
    }

    /** 固定列表编码（调用前先去重排序；空列表返回空串） */
    fun encodeFixed(times: List<Int>): String =
        times.filter { it in 0 until 1440 }.distinct().sorted().joinToString(",")

    /** 间隔循环编码；参数非法返回空串 */
    fun encodeInterval(start: Int, step: Int, end: Int): String =
        if (start in 0 until 1440 && end in 0 until 1440 && end >= start && step >= MIN_INTERVAL) {
            "$start/$step/$end"
        } else ""

    /** 人话显示："8:00、17:00" / "8:00 起每 60 分至 22:00"；空串返回空 */
    fun display(spec: String): String = when {
        spec.isBlank() -> ""
        isInterval(spec) -> parseInterval(spec)?.let { (s, st, e) ->
            "${Task.formatMin(s)} 起每 $st 分至 ${Task.formatMin(e)}"
        } ?: "时刻表无效"
        else -> parseFixed(spec).joinToString("、") { Task.formatMin(it) }
    }
}
