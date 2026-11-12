package com.norbury.taskalarm

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.color.MaterialColors
import com.norbury.taskalarm.data.AppDatabase
import com.norbury.taskalarm.data.RepeatRule
import com.norbury.taskalarm.data.StatsRetention
import com.norbury.taskalarm.data.StreakCalculator
import com.norbury.taskalarm.data.Task
import com.norbury.taskalarm.data.TaskRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * 统计页：概览 + 按优先级分组的任务明细。
 *
 * 明细直接往 LinearLayout 里 addView，不用 RecyclerView ——
 * 之前把 RecyclerView 套在 ScrollView 里，数据又是异步到的，
 * 测量过的行数不会随数据补上，表现就是「6 个任务只显示 3 个」。
 *
 * 口径：
 *  - 按天任务：连续/最长按天，完成率按近 30 天窗口
 *  - 账期任务（房租这类按日期重复）：一律按「期」算，趋势也改成按期的小格子
 *  - 分母为 0（刚建 / 本期未到期）显示「—」，不显示 0% 冤枉人
 *  - 已结束的任务（结束日已过）只保留一周，超期不再出现在本页（[StatsRetention]）；
 *    趋势格也只在"当天确实该做"的日子上色，非任务日的历史记录不占格子
 */
class StatsActivity : BaseActivity() {

    private var allRecords: List<TaskRecord> = emptyList()

    /** 最近一次渲染的数据，折叠切换时直接重排，不用回库 */
    private var lastRows: List<Pair<Task, StreakCalculator.StreakResult>> = emptyList()

    /** 折叠状态：任务级按 id、分组级按优先级值；页面内保持，重进页面重置 */
    private val collapsedTasks = mutableSetOf<Long>()
    private val collapsedGroups = mutableSetOf<Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stats)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        load()
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        lifecycleScope.launch {
            val dao = AppDatabase.get(this@StatsActivity).taskDao()
            val allTasks = withContext(Dispatchers.IO) { dao.getAll() }
            val today = AppDatabase.todayKey()

            /*
             * 已结束的任务（结束日已过 / 一次性任务已过期）在统计页最多再保留一周，
             * 超期不再显示（用户 2026-10-20：过期任务一直占着列表，正在进行的任务很难翻到）。
             * 只影响显示——完成记录仍留在库里，历史页与趋势核算不受影响。
             */
            val tasks = allTasks.filter { StatsRetention.keepInStats(it, today) }
            val hiddenEnded = allTasks.size - tasks.size

            // 拉取近 400 天记录，够算 streak 和 30 天趋势
            val fromKey = AppDatabase.keyDaysAgo(400)
            allRecords = withContext(Dispatchers.IO) { dao.getRecordsSince(fromKey) }
            val byTask = allRecords.groupBy { it.taskId }

            val rows = tasks.map { t ->
                val map = byTask[t.id]?.associate { it.dateKey to it.completed } ?: emptyMap()
                t to StreakCalculator.calculate(t, map)
            }

            // ── 全局概览 ──
            val todayRecords = withContext(Dispatchers.IO) { dao.getRecordsOfDate(today) }
            val doneToday = todayRecords.count { it.completed }
            val totalToday = tasks.count {
                RepeatRule.shouldTriggerTask(
                    it, today, Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
                )
            }

            // 本周（近 7 天，含今天）完成了多少件 —— 只报件数，不摆分母：
            // 「1 / 30」里的 30 是「天×任务」的乘积，读者根本算不过来
            var weekDone = 0
            for (i in 0 until 7) {
                val key = AppDatabase.keyDaysAgo(i)
                val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -i) }
                val dow = cal.get(Calendar.DAY_OF_WEEK)
                tasks.forEach { t ->
                    if (RepeatRule.shouldTriggerTask(t, key, dow) &&
                        byTask[t.id]?.any { it.dateKey == key && it.completed } == true
                    ) {
                        weekDone++
                    }
                }
            }

            val bestCurrent = rows.maxOfOrNull { it.second.current } ?: 0
            val expSum = rows.sumOf { it.second.expectedDays30 }
            val doneSum = rows.sumOf { it.second.doneDays30 }
            val rate = if (expSum == 0) 0 else doneSum * 100 / expSum

            val remaining = (totalToday - doneToday).coerceAtLeast(0)
            findViewById<TextView>(R.id.tvDoneToday).text = "$doneToday"
            findViewById<TextView>(R.id.tvTotalToday).text = "/ $totalToday"
            findViewById<TextView>(R.id.tvTodayNote).text = when {
                totalToday == 0 -> "今天没有要做的任务"
                remaining == 0 -> "今天的都做完了"
                else -> "还差 $remaining 件没做"
            }
            findViewById<TextView>(R.id.tvStreak).text = "$bestCurrent 天"
            findViewById<TextView>(R.id.tvRate).text = "$rate%"
            findViewById<TextView>(R.id.tvWeekDone).text = "$weekDone 件"

            // 进度条：今日完成度（两段 View 按权重填）
            // v3.34.86 修：就地改 weight 不会触发重新布局（视图已量过），必须走 layoutParams setter
            val ovPct = if (totalToday == 0) 100 else doneToday * 100 / totalToday
            setBar(
                findViewById(R.id.progressRateFill),
                findViewById(R.id.progressRateSpacer),
                ovPct / 100f
            )

            // ── 热力图（v3.34.72，GitHub 风格）：近 26 周每天的完成度 ──
            val recordsByDay = allRecords.groupBy { it.dateKey }
            val heat = HashMap<String, Float>()
            for (i in 181 downTo 0) {
                val key = AppDatabase.keyDaysAgo(i)
                val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -i) }
                val dowH = cal.get(Calendar.DAY_OF_WEEK)
                var due = 0
                var done = 0
                tasks.forEach { t ->
                    if (RepeatRule.shouldTriggerTask(t, key, dowH)) {
                        due++
                        if (recordsByDay[key]?.any { it.taskId == t.id && it.completed } == true) {
                            done++
                        }
                    }
                }
                if (due > 0) heat[key] = done.toFloat() / due
            }
            findViewById<com.norbury.taskalarm.util.HeatmapView>(R.id.heatmap).setData(heat)

            // 只在真有「还没到可统计阶段」的任务时才提示，平时不占地方
            val withData = rows.count { StreakCalculator.hasData(it.second) }
            val notStarted = tasks.size - withData
            val countTv = findViewById<TextView>(R.id.tvTaskCount)
            val notes = buildList {
                if (notStarted > 0) add("另有 $notStarted 个任务还没到可统计的时候（刚建或本期未到期）")
                if (hiddenEnded > 0) add("另有 $hiddenEnded 个已结束的任务已淡出统计（结束后保留一周）")
            }
            if (notes.isNotEmpty()) {
                countTv.visibility = View.VISIBLE
                countTv.text = notes.joinToString("；")
            } else {
                countTv.visibility = View.GONE
            }

            findViewById<View>(R.id.emptyStats).visibility =
                if (tasks.isEmpty()) View.VISIBLE else View.GONE
            renderList(rows)
        }
    }

    /** 按优先级分组渲染：高 / 中 / 低 各一段，组内「有数据在前、完成率低的在前」；组头可折叠 */
    private fun renderList(rows: List<Pair<Task, StreakCalculator.StreakResult>>) {
        lastRows = rows
        val list = findViewById<LinearLayout>(R.id.listStats)
        list.removeAllViews()

        rows.groupBy { it.first.priority }
            .toSortedMap()   // 0=高 → 2=低，正好是想要的先后
            .forEach { (priority, group) ->
                val ordered = group.sortedWith(
                    compareByDescending<Pair<Task, StreakCalculator.StreakResult>> {
                        StreakCalculator.hasData(it.second)
                    }.thenBy { StreakCalculator.completionRate(it.second) }
                        .thenBy { it.first.name }
                )
                list.addView(sectionHeader(priority, ordered.size))
                if (priority in collapsedGroups) return@forEach
                ordered.forEachIndexed { i, (task, res) ->
                    if (i > 0) list.addView(groupDivider())
                    list.addView(statRow(task, res))
                }
            }
    }

    /**
     * 两段式进度条：按比例分 weight（v3.34.86）。
     * 注意必须重新赋 layoutParams 才会 requestLayout —— 就地改 weight 在已量过的视图上不生效，
     * 表现就是「只有底槽、看不到已完成那一段」。
     */
    private fun setBar(fill: View, spacer: View, p: Float) {
        val ratio = p.coerceIn(0f, 1f)
        (fill.layoutParams as LinearLayout.LayoutParams).let {
            it.weight = ratio
            fill.layoutParams = it
        }
        (spacer.layoutParams as LinearLayout.LayoutParams).let {
            it.weight = 1f - ratio
            spacer.layoutParams = it
        }
    }

    /** 组内卡片之间的发丝分隔线：光靠留白，两张白卡在浅底上会糊成一片 */
    private fun groupDivider(): View {
        val d = resources.displayMetrics.density
        return View(this).apply {
            setBackgroundColor(
                MaterialColors.compositeARGBWithAlpha(
                    MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface),
                    28
                )
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (1 * d).toInt()
            ).apply {
                marginStart = (18 * d).toInt()
                marginEnd = (18 * d).toInt()
                bottomMargin = (10 * d).toInt()
            }
        }
    }

    /** 系统涟漪底：给纯 LinearLayout 一点点按反馈 */
    private fun tapBackground(): android.graphics.drawable.Drawable? {
        val a = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
        val dr = a.getDrawable(0)
        a.recycle()
        return dr
    }

    private fun priorityLabel(priority: Int): String = when (priority) {
        Task.PRIORITY_HIGH -> "高优先"
        Task.PRIORITY_LOW -> "低优先"
        else -> "中优先"
    }

    /** 组头：点一下整组收起 / 展开 */
    private fun sectionHeader(priority: Int, count: Int): View {
        val d = resources.displayMetrics.density
        val collapsed = priority in collapsedGroups
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = tapBackground()
            setPadding(0, (10 * d).toInt(), 0, (6 * d).toInt())
        }
        row.addView(TextView(this).apply {
            text = "${priorityLabel(priority)} · $count 个"
            textSize = 12.5f
            setTextColor(ContextCompat.getColor(this@StatsActivity, R.color.app_text_sub))
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_expand_more)
            setColorFilter(ContextCompat.getColor(this@StatsActivity, R.color.app_text_sub))
            rotation = if (collapsed) 0f else 180f   // 箭头朝上 = 可以收起
            val s = (18 * d).toInt()
            layoutParams = LinearLayout.LayoutParams(s, s)
        })
        row.setOnClickListener {
            if (collapsed) collapsedGroups.remove(priority) else collapsedGroups.add(priority)
            renderList(lastRows)
        }
        return row
    }

    private fun statRow(task: Task, res: StreakCalculator.StreakResult): View {
        val v = LayoutInflater.from(this).inflate(R.layout.item_stat, null, false)
        bindStat(v, task, res)
        return v
    }

    /* ---------------- 单行渲染 ---------------- */

    private fun bindStat(v: View, task: Task, res: StreakCalculator.StreakResult) {
        val periodic = res.isPeriodic
        val enough = StreakCalculator.hasData(res)
        val unit = if (periodic) " 期" else " 天"
        val rate = StreakCalculator.completionRate(res)
        val ctx = this

        // 任务名后面跟一个淡色小标签，一眼看出它属于哪个分组
        val nameTv = v.findViewById<TextView>(R.id.tvName)
        if (task.tag.isBlank()) {
            nameTv.text = task.name
        } else {
            val s = "${task.name}   ${task.tag}"
            val sp = SpannableString(s)
            val start = task.name.length
            sp.setSpan(RelativeSizeSpan(0.68f), start, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sp.setSpan(
                ForegroundColorSpan(ContextCompat.getColor(ctx, R.color.app_text_sub)),
                start, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            nameTv.text = sp
        }

        v.findViewById<TextView>(R.id.tvStreakVal).text = "${res.current}"
        v.findViewById<TextView>(R.id.tvLongestVal).text = "${res.longest}"
        v.findViewById<TextView>(R.id.tvStreakUnit).text = unit
        v.findViewById<TextView>(R.id.tvLongestUnit).text = unit

        // 分母为 0（刚建的任务 / 账期还没到期）显示「—」：
        // 写 0% 会被读成"我一次都没做"，其实是"还没到统计的时候"
        v.findViewById<TextView>(R.id.tvRateVal).text = if (enough) "$rate%" else "—"
        v.findViewById<TextView>(R.id.tvRateLabel).text = when {
            periodic -> "按期完成率"
            !enough -> "还没有可统计的日子"
            else -> "${res.windowDays}天完成率"
        }

        setBar(
            v.findViewById(R.id.progressFill),
            v.findViewById(R.id.progressSpacer),
            if (enough) rate / 100f else 0f
        )

        // 连续天数 > 0 时点亮火焰图标
        v.findViewById<ImageView>(R.id.icFlame).alpha = if (res.current > 0) 1f else 0.25f

        val trend = v.findViewById<LinearLayout>(R.id.trendRow)
        val caption = v.findViewById<TextView>(R.id.tvTrendCaption)
        if (periodic) {
            caption.text = "已经过去的账期 + 接下来 2 期"
            renderPeriods(trend, task)
        } else {
            caption.text = "近 30 天"
            renderDailyTrend(trend, task)
        }

        // ── 折叠：点名称行收起指标 / 进度条 / 趋势，只留标题 ──
        val collapsed = task.id in collapsedTasks
        v.findViewById<View>(R.id.rowMetrics).visibility = if (collapsed) View.GONE else View.VISIBLE
        v.findViewById<View>(R.id.progressRate).visibility = if (collapsed) View.GONE else View.VISIBLE
        trend.visibility = if (collapsed) View.GONE else View.VISIBLE
        caption.visibility = if (collapsed) View.GONE else View.VISIBLE
        v.findViewById<ImageView>(R.id.ivStatChevron).rotation = if (collapsed) 0f else 180f
        v.findViewById<View>(R.id.rowStatHead).setOnClickListener {
            if (collapsed) collapsedTasks.remove(task.id) else collapsedTasks.add(task.id)
            renderList(lastRows)
        }
    }

    /**
     * 趋势格的底色：用「左右内缩」画出来，不用 layout margin。
     * 原因：LinearLayout 的 weight 分配不扣 margin，30 个 2dp 间距会让整行比卡片宽 60dp
     * 溢出到卡片外（卡片默认不裁剪子视图），屏幕上就是卡片右边一串白锯齿。
     */
    private fun cellDrawable(color: Int, insetPx: Int): android.graphics.drawable.Drawable =
        android.graphics.drawable.InsetDrawable(
            android.graphics.drawable.ColorDrawable(color), insetPx, 0, insetPx, 0
        )

    /** 按天任务：30 根小柱 —— 完成=主色、漏了=错误色、未到期/创建前=底色 */
    private fun renderDailyTrend(trend: LinearLayout, task: Task) {
        trend.removeAllViews()
        val set = allRecords.filter { it.taskId == task.id }.associate { it.dateKey to it.completed }
        val density = resources.displayMetrics.density
        val creationKey = AppDatabase.keyOf(java.util.Date(task.createdAt))
        val todayKey = AppDatabase.todayKey()

        val doneColor = MaterialColors.getColor(trend, android.R.attr.colorPrimary)
        val missColor = MaterialColors.getColor(trend, android.R.attr.colorError)
        val idleColor = MaterialColors.getColor(
            trend, com.google.android.material.R.attr.colorOutlineVariant
        )

        for (i in 29 downTo 0) {
            val key = AppDatabase.keyDaysAgo(i)
            val cal = Calendar.getInstance()
            cal.add(Calendar.DAY_OF_YEAR, -i)
            val dow = cal.get(Calendar.DAY_OF_WEEK)

            val bar = View(this)
            bar.layoutParams = LinearLayout.LayoutParams(0, (17 * density).toInt(), 1f)

            val active = RepeatRule.shouldTriggerTask(task, key, dow)
            val done = set[key] == true
            // 只有"当天确实该做"才谈得上完成 / 漏做：任务改过期间或已经结束后，
            // 那些日子的完成记录不再占格子（用户 2026-10-20：过了结束期的任务
            // 不该再在统计里出现，否则会挤占正在进行的任务）
            // 只有"任务创建之后、今天之前"的计划日才可能算未完成；
            // 创建之前的日子和今天（还没到截止）都是"无数据"，保持底色，不冤枉人
            val isMissed = active && !done && key >= creationKey && key < todayKey
            bar.background = cellDrawable(
                when {
                    active && done -> doneColor
                    isMissed -> missColor
                    else -> idleColor
                },
                (1 * density).toInt()
            )
            // 任务创建之前的日子直接隐去：新任务左边一片灰底会被当成"漏了很多天"
            bar.alpha = when {
                isMissed || (active && done) -> 1f
                key < creationKey -> 0f
                else -> 0.35f
            }
            trend.addView(bar)
        }
    }

    /**
     * 账期任务（房租 / 账单）：按天画 30 根柱子毫无意义（一个月里只到一次账），
     * 改成最近 4 期 + 下一期的小格子，标着日期，一眼看出哪期交了哪期没交。
     */
    private fun renderPeriods(trend: LinearLayout, task: Task) {
        trend.removeAllViews()
        val density = resources.displayMetrics.density
        val today = AppDatabase.todayKey()

        fun isDue(key: String) = RepeatRule.isDateDue(
            task.anchorDate, task.intervalMonths, task.endDate, key,
            task.intervalDays, RepeatRule.dayMaskOf(task.repeatMask), task.monthsMask
        )

        val past = ArrayList<String>()
        val back = Calendar.getInstance()
        var guard = 0
        while (past.size < 4 && guard < 800) {
            guard++
            val key = AppDatabase.keyOf(back.time)
            if (key <= today && isDue(key)) past.add(key)
            back.add(Calendar.DAY_OF_YEAR, -1)
        }
        val upcoming = ArrayList<String>()
        val fwd = Calendar.getInstance()
        guard = 0
        while (upcoming.size < 2 && guard < 800) {
            guard++
            fwd.add(Calendar.DAY_OF_YEAR, 1)
            val key = AppDatabase.keyOf(fwd.time)
            if (isDue(key)) upcoming.add(key)
        }

        val set = allRecords.filter { it.taskId == task.id }.associate { it.dateKey to it.completed }
        val doneColor = MaterialColors.getColor(trend, android.R.attr.colorPrimary)
        val missColor = MaterialColors.getColor(trend, android.R.attr.colorError)
        val idleColor = MaterialColors.getColor(
            trend, com.google.android.material.R.attr.colorOutlineVariant
        )
        val onDone = MaterialColors.getColor(
            trend, com.google.android.material.R.attr.colorOnPrimary
        )
        val onMiss = MaterialColors.getColor(
            trend, com.google.android.material.R.attr.colorOnError
        )
        val onIdle = MaterialColors.getColor(
            trend, com.google.android.material.R.attr.colorOnSurfaceVariant
        )

        (past.reversed() + upcoming).forEach { key ->
            val future = key > today
            val done = set[key] == true
            val cell = TextView(this).apply {
                text = key.drop(5).replace('-', '/')   // 2026-10-20 → 10/20
                textSize = 11f
                gravity = Gravity.CENTER
                val p = (7 * density).toInt()
                setPadding(p, p, p, p)
                setTextColor(if (done) onDone else if (future) onIdle else onMiss)
                // 圆角底色左右各内缩 2dp 当空隙：不用 margin，免得 weight 分配把整行挤出卡片
                background = android.graphics.drawable.InsetDrawable(
                    GradientDrawable().apply {
                        cornerRadius = 8 * density
                        setColor(if (done) doneColor else if (future) idleColor else missColor)
                    },
                    (2 * density).toInt(), 0, (2 * density).toInt(), 0
                )
                alpha = if (future) 0.45f else 1f
            }
            // 固定宽度，不用 weight：只剩「下一期」一格时，weight 会让它横撑满整行
            trend.addView(cell, LinearLayout.LayoutParams(
                (54 * density).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }
    }
}
