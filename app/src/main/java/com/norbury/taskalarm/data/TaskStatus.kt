package com.norbury.taskalarm.data

/**
 * 任务「当前状态」的判定（纯函数，便于单测）。
 *
 * ## 为什么需要它
 *
 * 以前卡片右上角的状态 pill 是这样算的：
 * ```
 * t.finalCheckOn(today)  // = deadlineOn + postOffset
 * t.deadlineOn(today)    // = deadlineMin
 * ```
 * 而 `deadlineMin` 在时刻表模式下被同步成了**首个时刻**（见 TaskEditActivity.save）。
 * 于是喝水任务（11:25 / 17:25 两个固定时刻）：
 *  - 过了 11:25 → 立刻显示「已过截止」
 *  - 过了 13:25（11:25 + 复查延后 120 分）→ 显示「已超时未完成」
 *
 * 但 17:25 那次**还没到**啊 —— 用户 2026-10-22 实测发现的正是这个：
 * 「我完成了上午的任务，过了第一个固定时刻，后续的时刻就直接是过期截止」。
 *
 * 根因：多时刻被当成了「一个整体」来算时间状态。
 * 修法：多时刻任务的状态要看**当前该关注的那一个时刻**。
 *
 * ## 当前该关注哪个时刻（[focusTime]）
 *
 * 1. 有「已经过点却还没确认」的时刻（漏做的）→ 取**最晚**的那个
 *    （漏做比「下一个还没到」更要紧，该提示超时）
 * 2. 否则有「还没到的未确认时刻」→ 取**最近**的那个（还没开始，不该显示过期）
 * 3. 全部确认 → 兜底给最后一个（调用方走「已完成」分支）
 *
 * 单时刻任务（`timeSpec` 空）直接用 `deadlineOn(dateKey)`，
 * 换算出来的始/止与改动前**完全一致**（`preOffsetOf` 在 `timeSpec` 空时返回任务级值）。
 */
object TaskStatus {

    /** 卡片右上角的状态 pill 文案类别 */
    enum class Kind {
        /** 等待中：还没到这个时刻的开始时间 */
        WAITING,

        /** 催促中：已进入这个时刻的提前量区间 */
        URGING,

        /** 已过截止：目标时刻已过，但还没到复查（超时）点 */
        PAST_DEADLINE,

        /** 已超时未完成：连复查点都过了 */
        OVERDUE
    }

    /**
     * 多时刻任务当前该关注哪个时刻（返回该时刻的当日分钟数）。
     *
     * @param confirmed 已确认的时刻下标集合（多时刻任务的 `t下标` 条目）
     * @param nowMin 当前时刻的当日分钟数
     */
    fun focusTime(
        task: Task,
        confirmed: Set<Int>,
        dateKey: String,
        nowMin: Int
    ): Int {
        val times = task.timesOn(dateKey)
        if (times.isEmpty()) return task.deadlineOn(dateKey)
        // 单时刻：就是它自己（老任务行为零变化）
        if (times.size <= 1) return times.first()

        val unconfirmed = times.indices.filter { it !in confirmed }
        if (unconfirmed.isEmpty()) return times.last()

        // 1. 已经过点却还没确认 → 漏做了，取最晚的那个算状态（该提示超时）
        val missed = unconfirmed.filter { times[it] <= nowMin }
        if (missed.isNotEmpty()) return times[missed.last()]

        // 2. 剩下的都是还没到的 → 取最近的那个（还没开始，不该显示过期）
        return times[unconfirmed.first()]
    }

    /**
     * 当前状态。
     *
     * 判定顺序与改动前完全一致（超时 → 催促 → 过截止 → 等待），
     * 区别只在于多时刻任务算的是 [focusTime] 那个时刻，而不是首个时刻。
     */
    fun kind(
        task: Task,
        confirmed: Set<Int>,
        dateKey: String,
        nowMin: Int
    ): Kind {
        val tm = focusTime(task, confirmed, dateKey, nowMin)
        val pre = task.preOffsetOf(tm)
        val post = task.postOffsetOf(tm)
        val preAlert = tm - pre
        val finalCheck = tm + post
        return when {
            post >= 0 && nowMin >= finalCheck -> Kind.OVERDUE
            pre > 0 && nowMin >= preAlert -> Kind.URGING
            nowMin >= tm -> Kind.PAST_DEADLINE
            else -> Kind.WAITING
        }
    }
}
