package com.norbury.taktu.data

import java.util.Calendar

/**
 * 连续打卡（Streak）计算。
 *
 * 规则说明：
 *  - 「今天还没做」不算断。今天还没到截止，或还没勾，streak 不归零，
 *    否则用户一早就看到 streak 归零会很挫败。所以计算时从**昨天**往回数，
 *    如果今天已完成则再补上今天。
 *  - 只有命中重复规则的日子才纳入统计（比如工作日任务，周末不算断）。
 *  - 起点不早于任务创建日。
 */
object StreakCalculator {

    data class StreakResult(
        /** 当前连续天数（含今天，若今天已完成） */
        val current: Int,
        /** 历史最长连续天数 */
        val longest: Int,
        /** 今天是否已完成 */
        val doneToday: Boolean,
        /** 最近 30 天中按重复规则应做的天数 */
        val expectedDays30: Int,
        /** 最近 30 天中实际完成的天数 */
        val doneDays30: Int,
        /**
         * 完成率窗口**实际覆盖的日历天数**：任务创建不足 30 天时 = 创建以来的天数，否则 30。
         *
         * 存在的意义是把「30天完成率」这个标签改成诚实的「近N天完成率」——
         * 刚创建 3 天的任务 3/3 = 100% 本身没错，但顶着"30天"的名头就是误导，
         * 用户会觉得"我才做 3 天怎么就 100% 了"。
         */
        val windowDays: Int,
        /**
         * 创建至今的全部应做次数 / 已完成次数。
         * 账期类任务（房租、账单）一天只到一次账，用 30 天窗口看经常是 0 —— 那会显示成
         * 「0%」，其实只是本期还没到期。这类任务一律用全历史按「期」算。
         */
        val expectedAll: Int = 0,
        val doneAll: Int = 0,
        /** 是否账期型（按日期重复），统计口径与按天任务不同 */
        val isPeriodic: Boolean = false
    )

    fun calculate(
        task: Task,
        records: Map<String, Boolean>,   // dateKey -> completed
        todayKey: String = AppDatabase.todayKey()
    ): StreakResult {
        // 账期任务（房租/账单）可以提前完成，统计口径单独走：见 calculatePeriodic
        if (task.repeatType == RepeatRule.TYPE_DATE) {
            return calculatePeriodic(task, records, todayKey)
        }

        // ── 1. 当前连续 ──
        // 今天已完成则先记 1 天，随后**一律从昨天往回扫** ——
        // 早先写成"今天没做才往前推一天"，今天做了就会把今天数第二遍，
        // 连续天数凭空多一天（已被 StreakCalculatorTest 抓住）。
        val doneToday = records[todayKey] == true
        var current = if (doneToday) 1 else 0

        val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }

        val limitDays = 400
        for (i in 0 until limitDays) {
            val key = AppDatabase.keyOf(cal.time)
            // 不越过任务创建日
            if (AppDatabase.daysBetween(AppDatabase.keyOf(java.util.Date(task.createdAt)), key) < 0) break

            val dow = cal.get(Calendar.DAY_OF_WEEK)
            if (RepeatRule.shouldTriggerTask(task, key, dow)) {
                if (records[key] == true) {
                    current++
                } else {
                    break
                }
            }
            cal.add(Calendar.DAY_OF_YEAR, -1)
        }

        // ── 2. 历史最长 + 近 30 天统计 ──
        var longest = 0
        var run = 0
        var expectedDays30 = 0
        var doneDays30 = 0
        var expectedAll = 0
        var doneAll = 0

        // 从创建日扫到今天
        val scanCal = Calendar.getInstance()
        scanCal.time = java.util.Date(task.createdAt)
        scanCal.set(Calendar.HOUR_OF_DAY, 0)
        scanCal.set(Calendar.MINUTE, 0)
        scanCal.set(Calendar.SECOND, 0)
        scanCal.set(Calendar.MILLISECOND, 0)

        val todayCal = Calendar.getInstance()
        todayCal.set(Calendar.HOUR_OF_DAY, 0)
        todayCal.set(Calendar.MINUTE, 0)
        todayCal.set(Calendar.SECOND, 0)
        todayCal.set(Calendar.MILLISECOND, 0)

        // 窗口实际覆盖的日历天数：不满 30 天就按创建以来的天数算（标签用）
        val ageDays = AppDatabase.daysBetween(
            AppDatabase.keyOf(java.util.Date(task.createdAt)), todayKey
        )
        val windowDays = (ageDays + 1).coerceIn(1, 30)

        var guard = 0
        while (!scanCal.after(todayCal) && guard < 800) {
            guard++
            val key = AppDatabase.keyOf(scanCal.time)
            val dow = scanCal.get(Calendar.DAY_OF_WEEK)

            if (RepeatRule.shouldTriggerTask(task, key, dow)) {
                val done = records[key] == true
                if (done) {
                    run++
                    if (run > longest) longest = run
                } else {
                    // 今天不算中断（还没到时间做很正常）
                    if (key != todayKey) run = 0
                }

                expectedAll++
                if (done) doneAll++

                val daysAgo = AppDatabase.daysBetween(key, todayKey)
                if (daysAgo < 30) {
                    expectedDays30++
                    if (done) doneDays30++
                }
            }
            scanCal.add(Calendar.DAY_OF_YEAR, 1)
        }

        return StreakResult(
            current = current,
            longest = longest,
            doneToday = doneToday,
            expectedDays30 = expectedDays30,
            doneDays30 = doneDays30,
            windowDays = windowDays,
            expectedAll = expectedAll,
            doneAll = doneAll,
            isPeriodic = task.repeatType == RepeatRule.TYPE_DATE
        )
    }

    /**
     * 账期任务的统计：按「期」而不是按天。
     *
     * 关键点是**提前完成**：房租可以提前交，那时记录落在未来的到期日上。
     * 所以这里从创建日一直扫到未来（400 天），并且：
     *  - 已到期的期 → 计入应做
     *  - 未来的期 → 只有已经写了完成记录（说明提前交过）才计入，且算已完成；
     *    没交的未来的期不算"欠"，不拉低完成率
     *
     * 之前只扫到今天，提前交的那一期根本不在窗口里，用户点了完成、按期完成率纹丝不动。
     */
    private fun calculatePeriodic(
        task: Task,
        records: Map<String, Boolean>,
        todayKey: String
    ): StreakResult {
        val keys = ArrayList<String>()
        val cal = Calendar.getInstance().apply {
            time = java.util.Date(task.createdAt)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val limit = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 400) }
        var guard = 0
        while (!cal.after(limit) && guard < 800) {
            guard++
            val key = AppDatabase.keyOf(cal.time)
            if (RepeatRule.isDateDue(
                    task.anchorDate, task.intervalMonths, task.endDate, key,
                    task.intervalDays, RepeatRule.dayMaskOf(task.repeatMask), task.monthsMask
                )
            ) {
                keys.add(key)
            }
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }

        // 已到期 or 已提前完成 —— 这两类才算「该结清的期」
        val settled = keys.filter { it <= todayKey || records[it] == true }
        val done = settled.count { records[it] == true }

        // 连续期数：从最新一期往回数；还没到期又没完成的期不算断
        var current = 0
        for (k in settled.asReversed()) {
            if (records[k] == true) current++
            else if (k < todayKey) break
        }
        var longest = 0
        var run = 0
        for (k in settled) {
            if (records[k] == true) {
                run++
                if (run > longest) longest = run
            } else if (k < todayKey) {
                run = 0
            }
        }

        return StreakResult(
            current = current,
            longest = longest,
            doneToday = records[todayKey] == true,
            expectedDays30 = 0,
            doneDays30 = 0,
            windowDays = 0,
            expectedAll = settled.size,
            doneAll = done,
            isPeriodic = true
        )
    }

    /**
     * 完成率（0~100）：
     *  - 账期任务（房租/账单）按「期」算全历史完成率
     *  - 其余按近 30 天窗口
     */
    fun completionRate(result: StreakResult): Int = when {
        result.isPeriodic ->
            if (result.expectedAll == 0) 0 else result.doneAll * 100 / result.expectedAll
        result.expectedDays30 == 0 -> 0
        else -> result.doneDays30 * 100 / result.expectedDays30
    }

    /**
     * 有没有可统计的数据。分母为 0 表示「还没有到期日 / 刚建的任务」，
     * 这时界面该显示「—」而不是 0% —— 0% 会让人以为统计坏了或者自己啥也没做。
     */
    fun hasData(result: StreakResult): Boolean =
        if (result.isPeriodic) result.expectedAll > 0 else result.expectedDays30 > 0
}
