package com.norbury.taskalarm.data

import java.time.LocalDate

/**
 * 统计页的任务可见范围（纯函数，可单测）。
 *
 * 用户口径（2026-10-20）：**已经过了结束期的任务，在统计页最多再保留一个星期**，
 * 超期就不再出现——否则过期任务一直占着统计列表，正在进行的任务很难翻到。
 * 保留窗口只影响统计页的**显示**，完成记录仍留在库里（历史页、趋势照算）。
 *
 * 「已结束」的判定：
 *  - 任何类型只要设了结束日且已过去（endDate < 今天）；
 *  - 一次性任务（TYPE_ONCE）没有 endDate 时看 anchorDate（它的"结束时间"就是那一天）。
 * 循环任务（按星期没设结束日）没有"过期"一说，永不淡出。
 */
object StatsRetention {

    /** 结束后的保留天数：第 8 天起不再出现在统计页 */
    const val KEEP_DAYS = 7L

    /** 任务是否已结束（结束日 / 一次性任务的那一天已经过去） */
    fun isEnded(task: Task, todayKey: String): Boolean {
        if (task.endDate.isNotBlank()) return task.endDate < todayKey
        return task.repeatType == RepeatRule.TYPE_ONCE &&
            task.anchorDate.isNotBlank() && task.anchorDate < todayKey
    }

    /** 保留窗口的起点（含）：参照日期早于它就不再显示 */
    fun cutoffKey(todayKey: String): String =
        LocalDate.parse(todayKey).minusDays(KEEP_DAYS).toString()

    /**
     * 统计页要不要显示这条任务。
     * 没结束 → 显示；已结束 → 参照日期（结束日，一次性任务回落 anchorDate）
     * 在保留窗口内才显示，超期淡出。
     */
    fun keepInStats(task: Task, todayKey: String): Boolean {
        if (!isEnded(task, todayKey)) return true
        val ref = task.endDate.ifBlank { task.anchorDate }
        return ref.isNotBlank() && ref >= cutoffKey(todayKey)
    }
}
