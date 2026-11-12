package com.norbury.taktu.data

/**
 * 首页列表按页签构建（纯函数，可单测）。
 *
 * 为什么抽出来：折叠 / 展开分组时要免查库，直接用最近一次加载的全量任务重组列表，
 * 老代码在 toggleGroup 里把全量 latestTasks 直接提交上屏，「今天」页会一下子
 * 冒出所有未来的计划（v3.26.3 修复的 bug）。现在首次加载、切页签、折叠展开
 * 全部走这一个函数，口径永远一致。
 *
 * 历史页（v3.26.6 新增）：已完成 / 已过期的一次性任务从「今天」「全部」沉到
 * 「历史」，只保留 [HISTORY_KEEP_DAYS] 天 —— 7/1 做完的任务，8/1 起就看不到。
 * 保留窗口只影响**显示**，记录仍留在库里供统计。
 */
object TaskListBuilder {

    const val TAB_TODAY = 0
    const val TAB_PLAN = 1
    const val TAB_DONE = 2
    const val TAB_ALL = 3

    /** 历史：已完成 / 已过期的一次性任务沉淀页 */
    const val TAB_HISTORY = 4

    /** 历史保留窗口（天）≈ 一个月，超期不再显示（数据仍在库里，统计照算） */
    const val HISTORY_KEEP_DAYS = 30

    /** 全局排序口径：先按优先级（高→低），同档再按当天实际截止时间 */
    fun byPriorityThen(dateKey: String): Comparator<Task> =
        compareBy({ it.priority }, { it.deadlineOn(dateKey) })

    /**
     * 历史页的参照日期：原定日 / 结束日，取先有值的。
     * 用于「保留一个月」的判断与排序。空串表示没有日期依据，进不了历史。
     */
    fun historyRefKey(task: Task): String =
        task.endDate.ifBlank { task.anchorDate }

    /**
     * 是不是历史任务：已完成、或已过期的**一次性**任务；任何类型只要设了
     * 结束日且已过去（房租交完、系列任务结束）也算。
     * 循环任务（按星期）没有"过期"一说，永不进历史。
     *
     * @param done 该任务是否已完成（调用方查库后传入：原定日记录或今天的记录）
     */
    fun isHistorical(task: Task, todayKey: String, done: Boolean): Boolean {
        if (task.endDate.isNotBlank() && task.endDate < todayKey) return true
        if (task.repeatType != RepeatRule.TYPE_ONCE) return false
        if (task.anchorDate.isNotBlank() && task.anchorDate < todayKey) return true
        return done
    }

    /** 历史保留窗口的起点（含）：参照日期早于它就不再显示 */
    fun historyCutoffKey(todayKey: String): String =
        java.time.LocalDate.parse(todayKey).minusDays(HISTORY_KEEP_DAYS.toLong()).toString()

    /**
     * 当前页签要显示的任务列表：
     *  - 今天：今天该触发（含节假日 / 调休判定）且未完成
     *  - 已完成：今天该触发且已完成
     *  - 全部：不按今天过滤——未来的一次性任务、长期账期任务也给统一入口，
     *    但已完成 / 已过期的沉到「历史」，不在这里重复出现
     *  - 历史：已完成 / 已过期的一次性任务，参照日期在保留窗口内的
     *  - 计划：月历视图，没有任务分组可折叠，返回空列表
     *
     * @param historyDone 各任务在历史窗口内的完成情况（原定日 / 当日记录），
     *   历史与全部页需要；不传时只按今天记录判定
     */
    fun build(
        tab: Int,
        allTasks: List<Task>,
        doneToday: Map<Long, Boolean>,
        todayKey: String,
        dayOfWeek: Int,
        historyDone: Map<Long, Boolean> = emptyMap()
    ): List<Task> = when (tab) {
        TAB_TODAY -> allTasks.filter {
            RepeatRule.shouldTriggerTask(it, todayKey, dayOfWeek) && doneToday[it.id] != true
        }.sortedWith(byPriorityThen(todayKey))
        TAB_DONE -> allTasks.filter {
            RepeatRule.shouldTriggerTask(it, todayKey, dayOfWeek) && doneToday[it.id] == true
        }.sortedWith(byPriorityThen(todayKey))
        TAB_ALL -> allTasks
            .filterNot { isHistorical(it, todayKey, historyDone[it.id] == true) }
            .sortedBy { it.priority }
        TAB_HISTORY -> {
            val cutoff = historyCutoffKey(todayKey)
            allTasks.filter {
                isHistorical(it, todayKey, historyDone[it.id] == true || doneToday[it.id] == true)
            }.filter {
                val ref = historyRefKey(it)
                ref.isNotBlank() && ref >= cutoff
            }
                // 最近的在前：先按参照日期倒序，同一天再按优先级
                .sortedWith(
                    compareByDescending<Task> { historyRefKey(it) }.thenBy { it.priority }
                )
        }
        else -> emptyList()
    }
}
