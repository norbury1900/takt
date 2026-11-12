package com.norbury.taktu.data

/**
 * 桌面小部件「迷你时间轴」的纯数据层（v3.34.28，方向 C 的落地）。
 *
 * 思路：把 App 计划页那张竖向时间图微缩进桌面组件 —— 竖线串起今天的
 * 任务点、左侧时刻标签、陶土橘「现在」线横穿。与 TaskStatus 同纪律：
 * 本文件不碰 android.*，布局算法全部可单测；绘制在 widget/TimelineRenderer。
 *
 * 数据口径与 App 内部一致：
 *  - 多时刻任务每个时刻各占一行（v3.34.22 的教训：只列首时刻会误导）
 *  - 开始 = 目标 - 该时刻的提前量；结束 = 目标 + 该时刻的延后量（-1 = 关）
 *  - 完成态：多时刻逐时刻判（confirmedTimes 的 t下标），单时刻读 completed
 */

/** 时间轴上一行 = 一个「时刻」。多时刻任务的每个时刻各一行 */
data class TimelineEntry(
    val taskId: Long,
    val name: String,
    /** 目标时刻（当日分钟数） */
    val timeMin: Int,
    /** 催促（开始）时刻；提前量关闭时 = timeMin */
    val startMin: Int,
    /** 复查（结束）时刻；延后量关闭时 = timeMin */
    val endMin: Int,
    /** 这一行（这个时刻）是否已完成 */
    val done: Boolean,
    /** 进度后缀：「时刻 1/2」「2/3」「清单 1/4」，可叠加 */
    val sub: String = "",
    /** now 落在 [start, end) 且未完成 —— 正在催 */
    val urgent: Boolean = false,
    /** now 已过 end 且未完成 —— 该做没做 */
    val overdue: Boolean = false,
)

data class TimelineModel(
    /** 按开始时刻升序 */
    val entries: List<TimelineEntry>,
    val nowMin: Int,
    /** 今天任务总数（整条任务计 1，多时刻不重复计） */
    val total: Int,
    /** 其中已完成数 */
    val doneCount: Int,
) {
    /** 第一条还没做的行；null = 今天全部完成（底部按钮据此变文案） */
    val nextUndone: TimelineEntry?
        get() = entries.firstOrNull { !it.done }
}

/**
 * 由今天的任务 + 当日记录构建时间轴模型。
 *
 * @param records taskId → 当日 TaskRecord（没有记录传 null）
 */
fun buildTimeline(
    tasks: List<Task>,
    records: Map<Long, TaskRecord?>,
    todayKey: String,
    nowMin: Int,
): TimelineModel {
    val entries = mutableListOf<TimelineEntry>()
    var total = 0
    var done = 0
    for (t in tasks) {
        val rec = records[t.id]
        val multi = t.isMultiTime
        val times = t.timesOn(todayKey)
        // 任务级完成口径：多时刻要全部确认；单时刻读 completed
        val taskDone = if (multi) t.allTimesConfirmed(rec) else rec?.completed == true
        total++
        if (taskDone) done++
        for ((idx, tm) in times.withIndex()) {
            val pre = t.preOffsetOf(tm)
            val post = t.postOffsetOf(tm)
            val start = if (pre >= 0) tm - pre else tm
            val end = if (post >= 0) tm + post else tm
            // 择一提醒：任一时刻确认 = 整条完成，所有时刻行都跟随任务级完成（v3.34.111）
            val entryDone = if (t.eitherMode) taskDone else if (multi) {
                // 与 allTimesConfirmed 同口径：无 t 条目的老记录回落读 completed，
                // 否则升级后已完成的多时刻任务在组件上又变回「未完成」
                val confirmed = rec?.confirmedTimes ?: emptySet()
                if (confirmed.isEmpty()) rec?.completed == true
                else confirmed.contains(idx)
            } else rec?.completed == true
            val sub = buildString {
                if (multi) append("时刻 ${idx + 1}/${times.size}")
                if (t.targetCount > 0) {
                    if (isNotEmpty()) append(" · ")
                    append("${rec?.doneCount ?: 0}/${t.targetCount}")
                }
                if (t.itemList.isNotEmpty()) {
                    if (isNotEmpty()) append(" · ")
                    append("清单 ${rec?.checkedItemIndexes?.size ?: 0}/${t.itemList.size}")
                }
            }
            entries += TimelineEntry(
                taskId = t.id,
                name = t.name,
                timeMin = tm,
                startMin = start,
                endMin = end,
                done = entryDone,
                sub = sub,
                // start < end 才有「进行中」区间；准时响（start == end）到点即 overdue
                urgent = !entryDone && start < end && nowMin >= start && nowMin < end,
                overdue = !entryDone && nowMin >= end,
            )
        }
    }
    return TimelineModel(entries.sortedBy { it.startMin }, nowMin, total, done)
}

/** 布局后的一行：圆心 y（dp，相对位图顶部）+ 左侧时刻标签文案 */
data class PlacedEntry(
    val entry: TimelineEntry,
    val y: Float,
    val timeLabel: String,
)

data class TimelineLayout(
    val placed: List<PlacedEntry>,
    /** 「现在」线的 y（dp）；null = 今天没有任何行（不画）。位置跟随相邻任务行插值 */
    val nowY: Float?,
    val windowStart: Int,
    val windowEnd: Int,
    /** 今天没有任何行 */
    val empty: Boolean,
)

/**
 * 把模型布置到位图坐标（单位 dp，渲染层再乘密度）。
 *
 * 时间窗：最早开始 / 最晚结束各留 20 分钟余量，且必须包含 now；
 * 跨度不足 90 分钟时以 now 为中心撑开（任务太少时避免全挤在一角）。
 * 行防重叠：按 y 升序逐行下推，最小行距 30dp（空间不够时按均分收紧）。
 * 「现在」线：跟着**相邻任务行**插值，不按理想时间轴 —— 行被压缩后两者才对得上。
 */
fun layout(model: TimelineModel, heightDp: Float): TimelineLayout {
    val padTop = 12f
    val padBottom = 12f
    if (model.entries.isEmpty()) {
        return TimelineLayout(emptyList(), null, model.nowMin, model.nowMin, empty = true)
    }
    val starts = model.entries.map { it.startMin }
    val ends = model.entries.map { it.endMin }
    var w0 = (minOf(starts.min(), model.nowMin) - 20).coerceAtLeast(0)
    var w1 = (maxOf(ends.max(), model.nowMin) + 20).coerceAtMost(1440)
    if (w1 - w0 < 90) {
        val mid = (model.nowMin).coerceIn(45, 1395)
        w0 = (mid - 45).coerceAtLeast(0)
        w1 = (mid + 45).coerceAtMost(1440)
    }
    val span = (w1 - w0).coerceAtLeast(1)
    val avail = (heightDp - padTop - padBottom).coerceAtLeast(1f)
    fun yOf(t: Int): Float =
        padTop + (t - w0).toFloat() / span * avail

    val n = model.entries.size
    val minGap = minOf(30f, avail / n)
    // 两遍防重叠：先自上而下推开（不越上界），行多挤出底部时再自下而上收紧 ——
    // 以前单向推开后 coerceIn 会把挤出去的行全部钳到底边同一个 y，堆成重叠
    // （v3.34.60 用户实测「任务多的时候重叠」）
    val ys = model.entries.map { yOf(it.startMin) }.toMutableList()
    for (i in 1 until n) ys[i] = maxOf(ys[i], ys[i - 1] + minGap)
    ys[n - 1] = ys[n - 1].coerceIn(padTop, heightDp - padBottom)
    for (i in n - 2 downTo 0) ys[i] = minOf(ys[i], ys[i + 1] - minGap).coerceAtLeast(padTop)
    // entries 已按 startMin 升序，y 同序
    val placed = ys.mapIndexed { i, y ->
        PlacedEntry(model.entries[i], y, Task.formatMin(model.entries[i].startMin))
    }
    // 「现在」线按**相邻两行**插值，而不是按理想时间轴 yOf(now)（v3.34.98）：
    // 组件高度小时行会被上方推开/下方收紧，行不再落在真实时间位置；
    // 线若仍按时间轴画，就会跑到 17:10 那一行下面（用户实测「1 点却标在 17:10 后」）。
    // 改成跟着行插值：now 在两条任务之间 → 落在两行之间；早于首行 → 顶部；晚于末行 → 底部。
    val times = placed.map { it.entry.startMin }
    val nowY = when {
        placed.isEmpty() -> null
        model.nowMin <= times.first() -> padTop
        model.nowMin >= times.last() -> heightDp - padBottom
        else -> {
            var i = 0
            while (i < n - 2 && times[i + 1] <= model.nowMin) i++
            val t0 = times[i]
            val t1 = times[i + 1]
            val frac = if (t1 > t0) (model.nowMin - t0).toFloat() / (t1 - t0) else 0f
            ys[i] + frac * (ys[i + 1] - ys[i])
        }
    }
    return TimelineLayout(placed, nowY, w0, w1, empty = false)
}
