package com.norbury.taskalarm.data

/**
 * 免打扰窗口（v3.34.16）。
 *
 * 用途：考试、开会等不想被提醒打扰的时段。落在窗口内的响铃整体后移到窗口结束时刻
 * （用户原话：软考当天 08:30~12:30、14:30~16:30「闹钟忘后推……避免影响考试」）。
 * 例：8:50 的提醒 + 窗口 510–750 → 改到 12:30（750）响。
 *
 * 存储格式（Prefs 里的一个字符串）：
 *   "2026-10-24:510-750,870-990;2026-10-25:540-600"
 *  - 分号分隔不同天；冒号前是日期键（yyyy-MM-dd），冒号后是当天的窗口列表
 *  - 每个窗口 "开始分钟-结束分钟"（0..1439；结束可到 1440 表示到午夜整）
 *  - 空串 = 未设置
 *
 * 解析全程容错：任何一段畸形（坏日期、非数字、start>=end、越界）直接跳过——
 * 存进来的坏数据不该让闹钟排定崩掉。
 */
data class QuietWindow(
    val dateKey: String,
    val startMin: Int,
    val endMin: Int
) {
    /** 左闭右开：开始那一刻算打扰，结束那一刻已自由（12:30 整可以响） */
    fun contains(fireMin: Int): Boolean = fireMin in startMin until endMin
}

object QuietWindows {

    private val KEY_REGEX = Regex("""\d{4}-\d{2}-\d{2}""")

    /** 分钟数 → "HH:mm"（窗口展示用，如 510 → "08:30"） */
    fun fmtMin(min: Int): String = "%02d:%02d".format(min / 60, min % 60)

    /** 解析存储串。raw 为空/空白/乱码都返回空表，不抛异常 */
    fun parse(raw: String?): List<QuietWindow> {
        if (raw.isNullOrBlank()) return emptyList()
        val out = ArrayList<QuietWindow>()
        raw.split(';').forEach { daySeg ->
            val day = daySeg.trim()
            if (day.isEmpty()) return@forEach
            val ci = day.indexOf(':')
            if (ci <= 0) return@forEach
            val key = day.substring(0, ci).trim()
            if (!KEY_REGEX.matches(key)) return@forEach
            day.substring(ci + 1).split(',').forEach { rangeSeg ->
                val r = rangeSeg.trim()
                if (r.isEmpty()) return@forEach
                val di = r.indexOf('-')
                if (di <= 0) return@forEach
                val s = r.substring(0, di).trim().toIntOrNull() ?: return@forEach
                val e = r.substring(di + 1).trim().toIntOrNull() ?: return@forEach
                if (s < 0 || e > 1440 || s >= e) return@forEach
                out.add(QuietWindow(key, s, e))
            }
        }
        return out
    }

    /** 序列化：同日自动合并成一段，按日期 + 开始时间排序（稳定，便于 diff 与测试） */
    fun serialize(windows: List<QuietWindow>): String =
        windows.groupBy { it.dateKey }.toSortedMap().entries.joinToString(";") { (key, day) ->
            key + ":" + day.sortedBy { it.startMin }.joinToString(",") { "${it.startMin}-${it.endMin}" }
        }

    /**
     * 把某天的响铃时刻按窗口后移；不在任何窗口内则原样返回。
     *
     * 窗口可能互相重叠（510–750 与 700–990）：后移结果 750 仍落在第二个窗口里，
     * 所以要循环施加直到稳定。每次跳跃都严格递增（m < e 才跳，跳完 m = e），
     * 天然收敛，guard 只是防呆。
     */
    fun shiftFor(windows: List<QuietWindow>, dateKey: String, fireMin: Int): Int {
        if (windows.isEmpty()) return fireMin
        val day = windows.filter { it.dateKey == dateKey }
        if (day.isEmpty()) return fireMin
        var m = fireMin.coerceIn(0, 1440)
        var guard = 0
        while (guard++ < 32) {
            val hit = day.firstOrNull { it.contains(m) } ?: break
            m = hit.endMin
        }
        return m
    }

    /** 过期窗口清理：早于 todayKey 的删掉（昨天的窗口留着只会碍眼） */
    fun prunePast(windows: List<QuietWindow>, todayKey: String): List<QuietWindow> =
        windows.filter { it.dateKey >= todayKey }
}
