package com.norbury.taktu.data

/**
 * 过期数据自动清理（v3.34.104，用户 2026-11-01 需求）。
 *
 * 规则：
 *  - 过期数据最多保留 **1 个月**（30 天），超过的全部删掉
 *  - **每周**执行一次，App 自己跑，不需要用户操作（进程启动时检查，到点即清）
 *  - 「过期」= 不会再响的任务：一次性任务已过那天；或任何带结束日期的任务已过结束日
 *  - 按星期 / 按日期 / 农历**没有结束日期**的任务永远不过期（会一直循环）
 *
 * 本文件只放纯判定，方便单测；删库动作在 TaktuApp 里执行。
 */
object AutoPurge {

    /** 过期数据保留天数（用户口径：最多留最近一个月） */
    const val KEEP_DAYS = 30

    /** 清理间隔（毫秒）：每周一次 */
    const val INTERVAL_MS = 7L * 24 * 60 * 60 * 1000

    /**
     * 找出需要删除的任务 id。
     * @param cutoffKey 截止日（yyyy-MM-dd），早于它的视为过期；调用方传「今天 - KEEP_DAYS」
     */
    fun expiredTaskIds(tasks: List<Task>, cutoffKey: String): List<Long> =
        tasks.filter { t ->
            val ref = when (t.repeatType) {
                // 一次性任务：那天过了一个月就再也不会触发
                RepeatRule.TYPE_ONCE -> t.anchorDate
                // 其余：有结束日期且结束日期已过一个月才过期；没有结束日期 = 永久循环
                else -> t.endDate
            }
            ref.isNotBlank() && ref.length >= 10 && ref < cutoffKey
        }.map { it.id }
}
