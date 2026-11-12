package com.norbury.taktu.data

/**
 * 「几点几分」↔「提前 / 延后多少分钟」的换算与范围裁剪（v3.34.17）。
 *
 * 背景：编辑页原来让用户选「提前 90 分钟」这种相对量，读起来要自己心算；
 * 用户要求改成直接选「07:00」这种绝对钟点，并且**范围要合理**：
 *   - 开始时间（催促点）：只能落在 [00:00, 目标时刻] —— 不能晚于目标时间；
 *   - 截止时间（复查点）：只能落在 [目标时刻, 23:55] —— 不能早于目标时间。
 *
 * **落库字段不变**：仍然存偏移量（`preOffsetMin` / `postOffsetMin`），
 * 这样调度链路（AlarmScheduler.occurrences / catchUpMissed）与 JSON 契约
 * （urgeBefore / recheckAfter）都不用动，老任务也零迁移。
 *
 * 三态语义保持一致：-1 = 关闭，0 = 与目标同时响，>0 = 提前 / 延后 N 分钟。
 * 换成钟点后，「准时」= 选的目标时刻本身，语义自然对齐。
 *
 * 粒度与时间选择器一致：5 分钟一档。
 */
object ClockOffset {

    /** 钟点粒度（分钟）—— 与 TimeSheet / 时间选择器保持一致 */
    const val STEP = 5

    /** 当天最后一档（23:55）—— 23:59 不是 5 的倍数，取不到 */
    const val LAST_SLOT = 1435

    /** 开始时间（催促点）可选范围：00:00 ~ 目标时刻 */
    fun startRange(targetMin: Int): IntRange = 0..targetMin.coerceIn(0, LAST_SLOT)

    /** 截止时间（复查点）可选范围：目标时刻 ~ 23:55 */
    fun endRange(targetMin: Int): IntRange = targetMin.coerceIn(0, LAST_SLOT)..LAST_SLOT
    /** 钟点 → 提前量（分钟）。越界会被钳到范围内 */
    fun startOffset(targetMin: Int, clockMin: Int): Int {
        val c = clockMin.coerceIn(startRange(targetMin))
        return (targetMin - c).coerceIn(0, 1440)
    }

    /** 钟点 → 延后量（分钟）。越界会被钳到范围内 */
    fun endOffset(targetMin: Int, clockMin: Int): Int {
        val c = clockMin.coerceIn(endRange(targetMin))
        return (c - targetMin).coerceIn(0, 1440)
    }

    /**
     * 提前量 → 开始钟点。
     * 注意：**老数据可能越界**（例如目标 00:30、提前 90 分钟 → 真实催促点是前一天 23:00，
     * 新范围里没有这一档），这里只用于给弹窗一个合法的初始位置，
     * 不改动已经落库的偏移量 —— 偏移量只有用户自己动手选才会被重算。
     */
    fun startClock(targetMin: Int, offsetMin: Int): Int =
        (targetMin - offsetMin).coerceIn(startRange(targetMin))

    /** 延后量 → 截止钟点（同样只作弹窗初值，不回写偏移量） */
    fun endClock(targetMin: Int, offsetMin: Int): Int =
        (targetMin + offsetMin).coerceIn(endRange(targetMin))

    /**
     * 某小时下的合法分钟档（5 分钟一档，闭区间）。
     * @param from 该小时的起始分钟（含）
     * @param to   该小时的结束分钟（含）
     *
     * **返回的是区间内的绝对分钟**，不是「小时内的分数」。
     * 例：`minuteSlots(660, 719)` → [660, 665, …, 715]。
     * 分针滚轮要的是 0..59 的分数，请用 [minuteSlotsOfHour]。
     */
    fun minuteSlots(from: Int, to: Int): List<Int> {
        if (to < from) return emptyList()
        val start = ((from + STEP - 1) / STEP) * STEP      // 向上对齐到 5 的倍数
        val end = (to / STEP) * STEP                       // 向下对齐到 5 的倍数
        if (end < start) return emptyList()
        return (start..end step STEP).toList()
    }

    /**
     * 某小时内的合法分钟档，**返回该小时内的分数（0..59）** —— 分针滚轮直接用这个。
     *
     * v3.34.21 修 bug：以前弹窗拿 [minuteSlots] 的绝对分钟当分针显示，
     * 11 点那一小时滚出来的是 660/665/…/715，用户看到「分钟 680」这种非法值。
     * 这里统一成「小时内分数」，语义与滚轮一致。
     *
     * @param hour  目标小时（0..23）
     * @param minMin 允许范围下界（当日分钟数）
     * @param maxMin 允许范围上界（当日分钟数）
     */
    fun minuteSlotsOfHour(hour: Int, minMin: Int, maxMin: Int): List<Int> {
        val base = hour * 60
        val from = maxOf(minMin, base)
        val to = minOf(maxMin, base + 59)
        if (to < from) return emptyList()
        return minuteSlots(from, to).map { it - base }
    }

    /**
     * 范围 [minMin, maxMin] 内，哪些小时至少有一档可选分钟。
     * 没有可选分钟的小时不进滚轮（否则会出现「选到这个小时但分针是空的」）。
     */
    fun validHours(minMin: Int, maxMin: Int): List<Int> {
        val out = ArrayList<Int>()
        for (h in (minMin / 60)..(maxMin / 60)) {
            val from = maxOf(minMin, h * 60)
            val to = minOf(maxMin, h * 60 + 59)
            if (minuteSlots(from, to).isNotEmpty()) out.add(h)
        }
        return out
    }
}
