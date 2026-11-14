package com.norbury.taktu

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.norbury.taktu.util.ButtonTuner
import com.norbury.taktu.alarm.AlarmReceiver
import com.norbury.taktu.alarm.AlarmScheduler
import com.norbury.taktu.alarm.RingService
import com.norbury.taktu.data.AppDatabase
import com.norbury.taktu.data.CheckIn
import com.norbury.taktu.data.RepeatRule
import com.norbury.taktu.data.Task
import com.norbury.taktu.data.TaskListBuilder
import com.norbury.taktu.data.TaskRecord
import com.norbury.taktu.data.TaskStatus
import com.norbury.taktu.sheet.ConfirmSheet
import com.norbury.taktu.sheet.MonthSheet
import com.norbury.taktu.util.MiLinkBridge
import com.norbury.taktu.util.Permissions
import com.norbury.taktu.util.Prefs
import com.norbury.taktu.util.RingDrawable
import com.norbury.taktu.util.Ui
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

class MainActivity : BaseActivity() {

    // 历史 / 全部页的完成情况（保留窗口内记录）。loadTasks 时查一次，
    // toggleGroup 折叠展开分组时复用，避免免查库重组把已完成的任务抖掉
    private var historyDone: Map<Long, Boolean> = emptyMap()

    private lateinit var adapter: TaskAdapter
    private val doneToday = HashMap<Long, Boolean>()

    /** 分组折叠状态（跨启动记忆） */
    private val collapsedTags = HashSet<String>()

    /** 最近一次加载的任务列表，折叠/展开时免查库直接重组 */
    private var latestTasks: List<Task> = emptyList()

    /** 当前页签：今天 / 计划 / 已完成 / 全部 */
    private var tab = TAB_TODAY

    /** 历史页多选删除的管理模式（v3.34.142），离开历史页 / 删完自动退出 */
    private var manageMode = false

    /** 计划页的月历状态 */
    private var calYear = 0
    private var calMonth = 0
    private var calSelected = ""

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) toast("未授予通知权限，响铃可能看不到提示")
            refreshPermBanner()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        collapsedTags.addAll(Prefs.collapsedTags(this))
        adapter = TaskAdapter(
            onToggle = { task -> toggleDone(task) },
            onEdit = { task -> openEdit(task.id) },
            onToggleGroup = { tag -> toggleGroup(tag) },
            onSet = { task, delta ->
                // 「全部」页能看到未来的任务，但打卡只认今天 —— 非今天一律不写记录
                if (guardTodayOnly(task)) recordSet(task, delta)
            },
            onProgress = { task, done ->
                if (guardTodayOnly(task)) applyProgress(task, done)
            },
            onPickDay = { key -> pickCalendarDay(key) },
            onMonthPage = { y, m -> swipeCalendarMonth(y, m) },
            onPickMonth = { showMonthPicker() },
            onToggleItem = { task, index ->
                if (guardTodayOnly(task)) toggleItem(task, index)
            },
            onOpenCheckIn = { task ->
                startActivity(
                    Intent(this, RingTaskActivity::class.java)
                        .putExtra(RingTaskActivity.EXTRA_TASK_ID, task.id)
                        .putExtra(RingTaskActivity.EXTRA_FROM_RING, false)
                )
            },
            onToggleTime = { task, idx ->
                if (guardTodayOnly(task)) toggleTime(task, idx)
            }
        )
        findViewById<RecyclerView>(R.id.rvTasks).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }

        // 历史页多选删除（v3.34.142）：管理入口在顶栏，操作条浮在导航上方
        adapter.onSelectionChanged = { renderManageBar() }
        findViewById<TextView>(R.id.btnManage).setOnClickListener { setManage(!manageMode) }
        findViewById<View>(R.id.btnSelectAll).setOnClickListener { adapter.selectAllVisible() }
        findViewById<View>(R.id.btnDeleteSelected).setOnClickListener { confirmDeleteSelected() }

        findViewById<ImageButton>(R.id.btnNew).setOnClickListener { openEdit(-1L) }

        // 汉堡菜单（v3.34.69）：点开 / 收起；点任意一项先收起再跳。
        // 三项的 id 沿用旧顶栏按钮（btnImport/btnStats/btnSettings），只是从图标钮变菜单行
        val menu = findViewById<View>(R.id.boxMenu)
        findViewById<ImageButton>(R.id.btnMenu).setOnClickListener {
            menu.visibility = if (menu.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        fun goAndClose(cls: Class<*>) {
            menu.visibility = View.GONE
            startActivity(Intent(this, cls))
        }
        findViewById<View>(R.id.btnImport).setOnClickListener { goAndClose(ImportActivity::class.java) }
        findViewById<View>(R.id.btnStats).setOnClickListener { goAndClose(StatsActivity::class.java) }
        findViewById<View>(R.id.btnSettings).setOnClickListener { goAndClose(SettingsActivity::class.java) }

        // 页签切换（v3.34.64：底部悬浮导航，item 是竖排 LinearLayout）
        findViewById<View>(R.id.tabToday).setOnClickListener { switchTab(TAB_TODAY) }
        findViewById<View>(R.id.tabPlan).setOnClickListener { switchTab(TAB_PLAN) }
        findViewById<View>(R.id.tabDone).setOnClickListener { switchTab(TAB_DONE) }
        findViewById<View>(R.id.tabAll).setOnClickListener { switchTab(TAB_ALL) }
        findViewById<View>(R.id.tabHistory).setOnClickListener { switchTab(TAB_HISTORY) }
        renderTabs()
        findViewById<View>(R.id.permBanner).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // 底部导航真磨砂（v3.34.67）：BlurView 实时抓 listRoot 内容高斯模糊，
        // API31+ 走 RenderEffect（GPU），老系统退 RenderScript（BlurView 内置实现）
        val boxNav = findViewById<eightbitlab.com.blurview.BlurView>(R.id.boxNav)
        boxNav.setupWith(
            findViewById<ViewGroup>(R.id.listRoot),
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S)
                eightbitlab.com.blurview.RenderEffectBlur()
            else
                eightbitlab.com.blurview.RenderScriptBlur(this)
        )
            .setFrameClearDrawable(window.decorView.background)
            .setBlurRadius(18f)
            .setBlurAutoUpdate(true)
        // v3.34.74：磨砂画布默认是方的，胶囊圆角外会露出方形尖角 —— 按 outline 裁掉
        boxNav.clipToOutline = true

        // 汉堡菜单面板改真磨砂（v3.34.80）：与导航条同一套 BlurView 配置
        val boxMenuBlur = findViewById<eightbitlab.com.blurview.BlurView>(R.id.boxMenu)
        boxMenuBlur.setupWith(
            findViewById<ViewGroup>(R.id.listRoot),
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S)
                eightbitlab.com.blurview.RenderEffectBlur()
            else
                eightbitlab.com.blurview.RenderScriptBlur(this)
        )
            .setFrameClearDrawable(window.decorView.background)
            .setBlurRadius(18f)
            .setBlurAutoUpdate(true)
        boxMenuBlur.clipToOutline = true
    }

    private fun switchTab(which: Int) {
        if (tab == which) return
        tab = which
        if (manageMode) setManage(false)   // 离开历史页顺手退出多选
        findViewById<View>(R.id.boxMenu).visibility = View.GONE
        renderTabs()
        loadTasks()
    }

    private fun setEmpty(show: Boolean, titleRes: Int, subRes: Int) {
        findViewById<View>(R.id.emptyView).visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            findViewById<TextView>(R.id.emptyTitle).setText(titleRes)
            findViewById<TextView>(R.id.emptySubtitle).setText(subRes)
        }
    }

    /** 日程头日期：「10 月 27 日 · 星期二」（参考稿 agenda-head） */
    private fun agendaDate(key: String): String {
        val p = key.split("-")
        if (p.size != 3) return key
        val cal = java.util.Calendar.getInstance().apply {
            set(p[0].toInt(), p[1].toInt() - 1, p[2].toInt())
        }
        val weekRes = when (cal.get(java.util.Calendar.DAY_OF_WEEK)) {
            java.util.Calendar.SUNDAY -> R.string.week_sun
            java.util.Calendar.MONDAY -> R.string.week_mon
            java.util.Calendar.TUESDAY -> R.string.week_tue
            java.util.Calendar.WEDNESDAY -> R.string.week_wed
            java.util.Calendar.THURSDAY -> R.string.week_thu
            java.util.Calendar.FRIDAY -> R.string.week_fri
            else -> R.string.week_sat
        }
        return getString(
            R.string.agenda_date_fmt, p[1].toInt(), p[2].toInt(), getString(weekRes)
        )
    }

    private fun renderTabs() {
        Ui.navTab(findViewById(R.id.tabToday), tab == TAB_TODAY)
        Ui.navTab(findViewById(R.id.tabPlan), tab == TAB_PLAN)
        Ui.navTab(findViewById(R.id.tabDone), tab == TAB_DONE)
        Ui.navTab(findViewById(R.id.tabAll), tab == TAB_ALL)
        Ui.navTab(findViewById(R.id.tabHistory), tab == TAB_HISTORY)
        // 顶栏（v3.34.80）：汉堡常驻右上角，与其它页签位置统一；
        // 今天页不显示 TIME 标题（红卡本身就是页头），其余页签显示对应英文词
        val topTitle = findViewById<TextView>(R.id.tvTopTitle)
        val topMenu = findViewById<View>(R.id.btnMenu)
        topMenu.visibility = View.VISIBLE
        topTitle.visibility = View.VISIBLE
        // 「管理」入口只在历史页出现（v3.34.142 多选删除）
        findViewById<TextView>(R.id.btnManage).visibility =
            if (tab == TAB_HISTORY) View.VISIBLE else View.GONE
        topTitle.text = when (tab) {
            TAB_TODAY -> "Today"
            TAB_PLAN -> "Plan"
            TAB_DONE -> "Finish"
            TAB_ALL -> "All"
            else -> "History"
        }
        // 说明条：「全部」与「历史」才需要解释口径，平时隐藏
        val hint = findViewById<TextView>(R.id.tvAllHint)
        when (tab) {
            TAB_ALL -> {
                hint.visibility = View.VISIBLE
                hint.setText(R.string.all_hint)
            }
            TAB_HISTORY -> {
                hint.visibility = View.VISIBLE
                hint.setText(R.string.history_hint)
            }
            else -> hint.visibility = View.GONE
        }

        // 节假日数据过期提示（v3.34.123）：只在今天页显示，点 × 关闭本年度
        val holidayRow = findViewById<View>(R.id.holidayHintRow)
        val holidayKey = AppDatabase.todayKey()
        val holidayYear = holidayKey.take(4).toIntOrNull() ?: 0
        val showHolidayHint = tab == TAB_TODAY &&
                !com.norbury.taktu.util.HolidayCalendar.isCovered(holidayKey) &&
                Prefs.holidayHintYear(this) != holidayYear
        holidayRow.visibility = if (showHolidayHint) View.VISIBLE else View.GONE
        if (showHolidayHint) {
            findViewById<TextView>(R.id.tvHolidayHint).text =
                getString(R.string.holiday_hint_fmt, holidayYear)
        }
        findViewById<View>(R.id.btnHolidayHintClose).setOnClickListener {
            Prefs.setHolidayHintYear(this, holidayYear)
            holidayRow.visibility = View.GONE
        }
    }

    override fun onResume() {
        super.onResume()
        askNotificationIfNeeded()
        refreshPermBanner()
        // 跨天清零组数进度（v3.34.112）：App 常驻跨夜，回到前台时纠正
        TaktuApp.resetDailyDone(this)
        loadTasks()
        AlarmScheduler.rescheduleAll(this)
    }

    private fun askNotificationIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /** 顶部预警条：精确闹钟权限缺失时提示，这是最容易踩的坑 */
    private fun refreshPermBanner() {
        val banner = findViewById<View>(R.id.permBanner)
        val text = findViewById<TextView>(R.id.permBannerText)
        val exactOk = Permissions.canScheduleExactAlarms(this)
        val notifOk = Permissions.notificationsEnabled(this)

        when {
            !exactOk -> {
                banner.visibility = View.VISIBLE
                text.text = getString(R.string.perm_banner_exact)
            }
            !notifOk -> {
                banner.visibility = View.VISIBLE
                text.text = getString(R.string.perm_banner_notif)
            }
            else -> banner.visibility = View.GONE
        }
    }

    private fun loadTasks() {
        lifecycleScope.launch {
            val dao = AppDatabase.get(this@MainActivity).taskDao()
            val tasks = withContext(Dispatchers.IO) { dao.getAll() }
            val today = AppDatabase.todayKey()
            val records = withContext(Dispatchers.IO) {
                dao.getRecordsOfDate(today)
            }
            doneToday.clear()
            // 多时刻任务（v3.34.21）：「完成」必须是**全部时刻都确认过**，
            // 不能直接读record.completed —— 那上面写的 completed 只是最后
            // 一次写库时的整体布尔，一个时刻确认就会把整天标成完成。
            val byId = tasks.associateBy { it.id }
            records.forEach {
                val t = byId[it.taskId]
                doneToday[it.taskId] = if (t != null) t.allTimesConfirmed(it) else it.completed
            }
            // 清单状态（今天）：勾选下标 + 多组项的已做组数
            val itemChecks = HashMap<Long, Set<Int>>()
            val itemProg = HashMap<Long, Map<Int, Int>>()
            records.forEach {
                itemChecks[it.taskId] = it.checkedItemIndexes
                itemProg[it.taskId] = it.itemProgressMap
            }
            adapter.setItemProgress(itemProg)
            // 逐时刻确认状态（v3.34.21）：卡片上每个时刻一颗勾
            val timeConfirms = HashMap<Long, Set<Int>>()
            records.forEach { timeConfirms[it.taskId] = it.confirmedTimes }
            adapter.setTimeConfirms(timeConfirms)

            // 账期任务的「已完成」看本期的到期日，而不是今天：
            // 提前交了房租，卡片就该显示已完成，而不是等到 20 号才变
            val dated = tasks.filter { it.repeatType == RepeatRule.TYPE_DATE }
            val dueKeys = dated.mapNotNull { it.nextDueKeyAfter(today)?.takeIf { k -> k != today } }
                .distinct()
            if (dueKeys.isNotEmpty()) {
                val dueRecords = withContext(Dispatchers.IO) {
                    dueKeys.flatMap { dao.getRecordsOfDate(it) }
                }
                dated.forEach { t ->
                    val key = t.nextDueKeyAfter(today) ?: return@forEach
                    if (key == today) return@forEach
                    dueRecords.firstOrNull { it.dateKey == key && it.taskId == t.id }?.let {
                        doneToday[t.id] = t.allTimesConfirmed(it)
                        itemChecks[t.id] = it.checkedItemIndexes
                        itemProg[t.id] = it.itemProgressMap
                    }
                }
            }
            adapter.setItemChecks(itemChecks)
            latestTasks = tasks

            /*
             * 历史 / 全部页要知道「原定日那天做没做完」：今天完成的记录不够 ——
             * 昨天到期的一次性任务，完成记录在昨天。一次查询拿保留窗口内的
             * 全部记录，任一天完成就算已完成（一次性任务只有一天有效，无歧义）。
             * 存成成员变量：toggleGroup 折叠展开分组时免查库重组也要用它，
             * 否则历史页一折叠分组，已完成的任务就会凭空消失（v3.26.6 修复）。
             */
            if (tab == TAB_ALL || tab == TAB_HISTORY) {
                val cutoff = TaskListBuilder.historyCutoffKey(today)
                val histRecords = withContext(Dispatchers.IO) { dao.getRecordsSince(cutoff) }
                historyDone = histRecords.groupBy { it.taskId }
                    .mapValues { (_, recs) -> recs.any { it.completed } }
            } else {
                historyDone = emptyMap()
            }

            val dow = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
            when (tab) {
                TAB_TODAY, TAB_DONE, TAB_ALL, TAB_HISTORY -> {
                    /*
                     * 列表口径统一走 TaskListBuilder.build：今天 / 已完成按今日筛选，
                     * 全部不筛（但排除已沉到历史的任务），历史按参照日期筛。
                     * 折叠展开分组时也走同一个函数重组（见 toggleGroup），
                     * 保证任何时刻「今天」页都不会冒出未来的计划（v3.26.3 修复）。
                     */
                    val list = TaskListBuilder.build(
                        tab, latestTasks, doneToday, today, dow, historyDone
                    )
                    // 历史页的完成态必须用 historyDone（保留窗口内任一天完成）：
                    // 历史任务的完成记录在**原定日**不在今天，传 doneToday 会让
                    // 历史页里已完成的任务全部错显「已过期」（用户 2026-10-22 实测）
                    val doneForTab = if (tab == TAB_HISTORY) historyDone else doneToday
                    // 今日概览（v3.34.69，参考稿 dashboard 的 greeting 区）：
                    // 问候 + 日期 + 完成度，红色渐变卡压住整页玻璃；只在「今天」页出
                    // v3.34.76 修口径：分母 = 今天该触发的任务全集，完成数从**同一集合**
                    // 里数。旧写法 doneToday.count{it.value} 会把账期提前交、当天遗留
                    // 记录这些「不在今天口径里」的完成也数进分子 —— 分子分母不同源，
                    // 进度条会失真。现在 triggered 严格二分成 list(未完成)+doneCnt。
                    val overview = if (tab == TAB_TODAY) {
                        val triggered = latestTasks.filter {
                            RepeatRule.shouldTriggerTask(it, today, dow)
                        }
                        val doneCnt = triggered.count { doneToday[it.id] == true }
                        val total = triggered.size
                        val hi = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
                            in 0..11 -> "早上好"
                            in 12..17 -> "下午好"
                            else -> "晚上好"
                        }
                        val sub = if (total == 0) getString(R.string.overview_free)
                        else getString(R.string.overview_fmt, total, doneCnt, total - doneCnt)
                        TaskAdapter.Row.Overview(
                            hi, sub, if (total == 0) 0f else doneCnt.toFloat() / total
                        )
                    } else {
                        null
                    }
                    adapter.submit(
                        list, doneForTab, collapsedTags,
                        history = tab == TAB_HISTORY, overview = overview
                    )
                    val (titleRes, subRes) = when (tab) {
                        TAB_DONE -> R.string.done_empty_title to R.string.empty_subtitle
                        TAB_HISTORY -> R.string.history_empty_title to R.string.history_empty_desc
                        else -> R.string.empty_title to R.string.empty_subtitle
                    }
                    // 「今天」页有概览卡兜底（空态文案在卡里），不再叠全屏空态
                    setEmpty(list.isEmpty() && tab != TAB_TODAY, titleRes, subRes)
                }
                else -> {
                    // 计划：月历 + 选中那一天的任务
                    loadPlan()
                }
            }
            // 数据变了同步刷新桌面小部件（无小部件在桌面时是空操作）
            com.norbury.taktu.widget.TodayWidget.refresh(this@MainActivity)
        }
    }

    /** 计划页：月历 + 选中那天的竖向时间图（事项列表已取消 —— 与时间图重复，v3.34.4） */
    private fun loadPlan() {
        lifecycleScope.launch {
            if (calYear == 0) {
                val now = Calendar.getInstance()
                calYear = now.get(Calendar.YEAR)
                calMonth = now.get(Calendar.MONTH)
                calSelected = AppDatabase.todayKey()
            }

            val grid = com.norbury.taktu.util.CalendarGrid
            // 记录窗口 = 月历翻页窗口（v3.34.12 ViewPager2 预渲染相邻月也要状态点）
            val from = "$CAL_PAGE_START_YEAR-01-01"

            // 翻页窗口内的完成记录，按天归拢
            val records = withContext(Dispatchers.IO) {
                AppDatabase.get(this@MainActivity).taskDao().getRecordsSince(from)
            }
            val byDay = HashMap<String, MutableList<TaskRecord>>()
            records.forEach { byDay.getOrPut(it.dateKey) { ArrayList() }.add(it) }

            val today = AppDatabase.todayKey()

            val pickedTasks = grid.tasksOn(latestTasks, calSelected)
                .sortedWith(TaskListBuilder.byPriorityThen(calSelected))

            // 时间图：有任务且起止真的拉开才画（全是同刻任务画出来一列竖线，没信息量）
            // v3.34.105：日程行按时间排（用户 2026-11-03：20:00 的排在 07:00 前面）——
            // 之前沿用「优先级→时间」排序，甘特位置按时间、行序却按优先级，两不对齐
            val tl = run {
                val items = pickedTasks.map { t ->
                    val rec = byDay[calSelected]?.firstOrNull { it.taskId == t.id }
                    // v3.34.22：多时刻任务取「当前该关注的那一个时刻」（已确认的跳过），
                    // 而不是死盯首时刻 —— 上午那次做完后，时间图该指向下午那次。
                    val dl = if (t.isMultiTime) {
                        val times = t.timesOn(calSelected)
                        val confirmed = rec?.confirmedTimes ?: emptySet()
                        val pending = times.indices.filter { it !in confirmed }
                        times.getOrNull(pending.firstOrNull() ?: 0) ?: t.deadlineOn(calSelected)
                    } else {
                        t.deadlineOn(calSelected)
                    }
                    val start =
                        if (t.preOffsetOf(dl) > 0) dl - t.preOffsetOf(dl) else dl
                    TimelineTask(t.name, start, dl, t.allTimesConfirmed(rec))
                }.sortedWith(compareBy({ it.startMin }, { it.targetMin }))
                if (items.isEmpty()) null
                else {
                    val lo = items.minOf { it.startMin }
                    val hi = items.maxOf { it.targetMin }
                    if (hi > lo) TimelineRow(
                        lo, hi, items, nowMin(today, calSelected), agendaDate(calSelected)
                    ) else null
                }
            }
            // 没有时间图时给一句兜底：没任务 / 全是同刻任务
            val hint = when {
                tl != null -> null
                pickedTasks.isEmpty() -> getString(R.string.cal_day_empty)
                else -> getString(R.string.timeline_flat_hint)
            }

            adapter.submitCalendar(calYear, calMonth, calSelected, latestTasks, byDay, tl, hint)
            setEmpty(false, 0, 0)
        }
    }

    /** 选中日就是今天时返回当天当前时分（分钟），否则 -1（时间图不画「现在」线） */
    private fun nowMin(today: String, selected: String): Int {
        if (selected != today) return -1
        val now = Calendar.getInstance()
        return now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
    }

    /** 点月历上的某一天；点到上/下月的补位日期时顺带跳到那个月 */
    private fun pickCalendarDay(key: String) {
        calSelected = key
        val parts = key.split("-")
        val y = parts.getOrNull(0)?.toIntOrNull()
        val m = (parts.getOrNull(1)?.toIntOrNull() ?: 1) - 1
        if (y != null && (y != calYear || m != calMonth)) {
            calYear = y
            calMonth = m
        }
        loadPlan()
    }

    /** 月历左右滑（ViewPager2 翻页）落到某月（会自动跨年）；不改已选中的日期 */
    private fun swipeCalendarMonth(y: Int, m: Int) {
        if (y == calYear && m == calMonth) return
        calYear = y
        calMonth = m
        loadPlan()
    }

    /**
     * 月份选择弹窗（MonthSheet，与全站底部弹窗同视觉）。
     * 点月份 = 跳过去并刷新（行为与左右按钮一致：不改已选中的日期）。
     */
    private fun showMonthPicker() {
        MonthSheet(this, calYear, calMonth) { y, m ->
            calYear = y
            calMonth = m
            loadPlan()
        }.show()
    }

    /**
     * 「全部」页能把未来的任务也列出来，但**打卡记录只认今天**。
     * 非今天的任务只允许看和改（编辑），不允许打勾/拖进度 ——
     * 否则会给明天才做的任务记一条"今天已完成"，污染统计。
     * @return true = 今天该做，放行；false = 已提示并拦截
     */
    private fun guardTodayOnly(task: Task): Boolean {
        // 账期任务（房租 / 账单）允许提前完成：房东提前要就提前交，不该被"今天不是它的日子"拦住
        if (task.repeatType == RepeatRule.TYPE_DATE) {
            if (task.nextDueKeyAfter(AppDatabase.todayKey()) != null) return true
            toast("「${task.name}」的账期已经结束了")
            return false
        }
        val now = Calendar.getInstance()
        val due = RepeatRule.shouldTriggerTask(
            task, AppDatabase.todayKey(), now.get(Calendar.DAY_OF_WEEK)
        )
        if (!due) {
            toast("今天不是「${task.name}」的日子，只能查看和修改")
            return false
        }
        return true
    }

    /**
     * 勾 / 取消某一个时刻（v3.34.21）。**只有全部时刻都确认了，任务才算今天完成** ——
     * 这正是用户抱怨的那个点：一个时刻勾掉就把整个任务标成已完成。
     */
    private fun toggleTime(task: Task, index: Int) {
        tickHaptic()
        lifecycleScope.launch {
            val dao = AppDatabase.get(this@MainActivity).taskDao()
            val msg = withContext(Dispatchers.IO) {
                val times = task.timesOn(AppDatabase.todayKey())
                val total = times.size
                var wasOn = false
                val updated = CheckIn.update(this@MainActivity, dao, task) { rec ->
                    wasOn = index in rec.confirmedTimes
                    if (wasOn) rec.unconfirmTime(index, total)
                    else rec.confirmTime(index, total)
                }
                val timeMin = times.getOrNull(index) ?: 0
                val left = total - updated.confirmedTimes.size
                when {
                    wasOn && updated.completed -> "「${task.name}」${Task.formatMin(timeMin)} 已取消，还剩 $left 个时刻"
                    wasOn -> "「${task.name}」${Task.formatMin(timeMin)} 已取消"
                    updated.completed -> {
                        RingService.stop(this@MainActivity)
                        "「${task.name}」今天全部时刻都完成了"
                    }
                    else -> "「${task.name}」${Task.formatMin(timeMin)} 已完成，还剩 $left 个时刻"
                }
            }
            toast(msg)
            loadTasks()
            // 打卡不改时间配置，不需要全量重排闹钟（v3.34.114）
        }
    }

    /**
     * 首页那颗「完成」勾。
     *
     * v3.34.21 分流：多时刻任务**不再整体完成** —— 点一下只确认当前该确认的那个时刻
     * （响过的那次优先，否则最早未确认的那个），全部确认后再点才退回到整天未完成。
     * 用户原话：「中午那个响了、我勾选后，任务直接显示已完成，但另一个固定时刻
     * 还没到、还没响，不应该提前显示已完成。」
     */
    private fun toggleDone(task: Task) {
        tickHaptic()
        if (!guardTodayOnly(task)) return
        if (task.isMultiTime) {
            toggleNextTime(task)
            return
        }
        lifecycleScope.launch {
            val dao = AppDatabase.get(this@MainActivity).taskDao()
            val nowDone = doneToday[task.id] != true
            // 账期任务记在本期到期日（可以提前交）；其余任务就记今天
            val key = task.recordKeyOn(AppDatabase.todayKey())
            withContext(Dispatchers.IO) {
                // 完成/取消都走统一写入口；先读当天记录再改字段 —— 直接新建 TaskRecord
                // 会把清单勾选和每项组数冲掉，属于不可逆的数据丢失（v3.34.117）
                CheckIn.update(this@MainActivity, dao, task) { base ->
                    base.copy(
                        completed = nowDone,
                        doneCount = if (nowDone && task.targetCount > 0) task.targetCount else 0
                    )
                }
            }
            if (nowDone) {
                // 完成后把这个任务正在响的铃关掉
                RingService.stop(this@MainActivity)
                toast(
                    if (task.repeatType == RepeatRule.TYPE_DATE)
                        "已完成「${task.name}」，本期（$key）不会再提醒"
                    else "已完成「${task.name}」，今天不会再提醒"
                )
            }
            loadTasks()
        }
    }

    /**
     * 多时刻任务：确认 / 取消某一个时刻。
     *
     * 挑哪个时刻：[earliestDueUnconfirmed] —— 今天已经到点（响铃时刻已过）的
     * 那个优先，否则取最早未确认的。全都确认过了 → 这次点击是「退回整天未完成」。
     */
    private fun toggleNextTime(task: Task) {
        lifecycleScope.launch {
            val dao = AppDatabase.get(this@MainActivity).taskDao()
            val msg = withContext(Dispatchers.IO) {
                val times = task.timesOn(AppDatabase.todayKey())
                val total = times.size
                var pickedIdx = -1
                var reset = false
                val updated = CheckIn.update(this@MainActivity, dao, task) { rec ->
                    val idx = earliestDueUnconfirmed(task, times, rec)
                    if (idx < 0) {
                        reset = true
                        rec.clearConfirmedTimes()
                    } else {
                        pickedIdx = idx
                        rec.confirmTime(idx, total)
                    }
                }
                if (reset) {
                    // 全部确认过 → 整体退回
                    return@withContext "「${task.name}」已重置为今天未完成"
                }
                val left = total - updated.confirmedTimes.size
                if (updated.completed) {
                    RingService.stop(this@MainActivity)
                    "「${task.name}」${Task.formatMin(times[pickedIdx])} 已完成，今天全部做完了"
                } else {
                    "「${task.name}」${Task.formatMin(times[pickedIdx])} 已完成，还剩 $left 个时刻"
                }
            }
            toast(msg)
            loadTasks()
        }
    }

    /**
     * 该确认哪个时刻：响铃时刻已经过去的未确认时刻优先（那就是刚响的那次），
     * 否则取最早一个未确认的。全确认过返回 -1。
     */
    private fun earliestDueUnconfirmed(
        task: Task,
        times: List<Int>,
        rec: TaskRecord?
    ): Int {
        val nowMin = Calendar.getInstance().let {
            it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE)
        }
        val due = times.indices.filter { idx ->
            !task.timeConfirmed(rec, idx) &&
                task.finalCheckOfTime(times[idx]).let { ((it % 1440) + 1440) % 1440 <= nowMin }
        }
        return due.firstOrNull() ?: task.earliestUnconfirmed(rec)
    }

    private fun openEdit(id: Long) {
        startActivity(Intent(this, TaskEditActivity::class.java).apply {
            putExtra(TaskEditActivity.EXTRA_TASK_ID, id)
        })
    }

    /** 折叠 / 展开某个分组 */
    private fun toggleGroup(tag: String) {
        // 计划页是月历视图，没有任务分组，防御性拦一下
        if (tab == TAB_PLAN) return
        if (!collapsedTags.remove(tag)) collapsedTags.add(tag)
        Prefs.setCollapsedTags(this, collapsedTags)
        // 重组必须走当前页签的筛选口径：曾在这里直接提交全量 latestTasks，
        // 导致「今天」页折叠再展开分组后，未来的计划全部混上屏（v3.26.3 修复）。
        // 历史 / 全部页还必须带上 historyDone 与 history 标志（v3.26.6 补）：
        // 少了这两个，历史页一折叠分组，已完成的任务就消失、卡片状态也错乱。
        val today = AppDatabase.todayKey()
        val dow = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
        // 折叠展开免查库重组也要用对完成态：历史页同样得用 historyDone，
        // 不然一折叠分组，已完成的历史任务就变回「已过期」（2026-10-22 同 bug 第二现场）
        val doneForTab = if (tab == TAB_HISTORY) historyDone else doneToday
        val list = TaskListBuilder.build(tab, latestTasks, doneToday, today, dow, historyDone)
        adapter.submit(list, doneForTab, collapsedTags, history = tab == TAB_HISTORY)
    }

    /**
     * 勾 / 取消清单里的一项。全部勾完即视为任务完成（会停铃、卡片变已完成）；
     * 取消勾选会把完成状态退回去。账期任务记在本期到期日（可提前办），其余记今天。
     *
     * v3.34.21：写库走 [TaskRecord.mergeCheckedItems] 保住已确认的时刻条目；
     * 多时刻任务的「完成」按 [Task.allTimesConfirmed] 判定 —— 清单勾完只是
     * 清单那部分做完，不代表整天做完了。
     */
    private fun toggleItem(task: Task, index: Int) {
        tickHaptic()
        // 历史 / 全部页也能看到过去和未来的任务，勾清单会把记录写到今天 ——
        // 统计会被污染，明天回看状态还对不上。和打卡一样只放行"今天该做的"
        // （账期任务可提前办，guardTodayOnly 内部放行）。
        if (!guardTodayOnly(task)) return
        lifecycleScope.launch {
            val dao = AppDatabase.get(this@MainActivity).taskDao()
            val total = task.itemList.size
            val multi = task.isMultiTime
            val allDone = withContext(Dispatchers.IO) {
                // 先合并再定完成态：合并后的记录里 t 条目还在，allTimesConfirmed 才算得准
                var itemsDone = false
                val committed = CheckIn.update(this@MainActivity, dao, task) { rec ->
                    val cur = rec.checkedItemIndexes
                    val next = if (index in cur) cur - index else cur + index
                    itemsDone = total > 0 && next.size >= total
                    rec.mergeCheckedItems(next).copy(
                        completed = if (multi) rec.completed else itemsDone,
                        // 清单勾完且是组数任务时，任务表计数同步到位（旧行为）
                        doneCount = if (itemsDone && task.targetCount > 0) task.targetCount else rec.doneCount
                    )
                }
                itemsDone to committed.completed
            }
            if (allDone.first) {
                if (allDone.second) RingService.stop(this@MainActivity)
                toast("「${task.name}」清单已勾完")
            }
            loadTasks()
        }
    }

    /**
     * 逐组打卡：delta = +1 记一组，-1 退一组。
     * 以数据库最新值为基准递推，快速连点也不会少记/多记；
     * 做满最后一组自动写完成记录，当天后续提醒全部静音。
     *
     * v3.34.21：多时刻任务即使次数做满也**不置整天完成**（除非全部时刻都确认过），
     * 且写库必须保住已确认的 `t` 条目 —— 以前整条重写会把它们清零。
     */
    private fun recordSet(task: Task, delta: Int) {
        tickHaptic()
        if (!guardTodayOnly(task)) return
        lifecycleScope.launch {
            val dao = AppDatabase.get(this@MainActivity).taskDao()
            val newDone = withContext(Dispatchers.IO) {
                val fresh = dao.getById(task.id) ?: return@withContext null
                val step = stepOf(fresh)
                val target = fresh.targetCount.coerceAtLeast(1)
                // 基准取当天记录：Task.doneCount 跨天可能是昨天的满额（v3.34.112）
                var done = 0
                val committed = CheckIn.update(this@MainActivity, dao, fresh) { rec ->
                    done = (rec.doneCount + delta * step).coerceIn(0, target)
                    val full = done >= target
                    rec.copy(
                        doneCount = done,
                        completed = if (fresh.isMultiTime) rec.completed else full
                    )
                }
                done to committed.completed
            } ?: return@launch

            if (task.targetCount > 0 && newDone.first >= task.targetCount) {
                if (newDone.second) RingService.stop(this@MainActivity)
                toast(
                    if (newDone.second) "「${task.name}」已完成，今天不会再提醒"
                    else "「${task.name}」次数已做满，还有时刻没确认"
                )
            }
            loadTasks()
        }
    }

    /**
     * 拖动进度条提交：直接把完成数设为 newDone（而不是累加），
     * 这样来回拖动总能落到准确值，不会越拖越偏。
     */
    private fun applyProgress(task: Task, newDone: Int) {
        tickHaptic()
        if (!guardTodayOnly(task)) return
        val wasDone = doneToday[task.id] == true
        lifecycleScope.launch {
            val dao = AppDatabase.get(this@MainActivity).taskDao()
            val reached = withContext(Dispatchers.IO) {
                val fresh = dao.getById(task.id) ?: return@withContext null
                val t = fresh.targetCount.coerceAtLeast(1)
                val d = newDone.coerceIn(0, t)
                /*
                 * 这里以前有一句「次数没变化就不写记录」的早退 —— 那是个 bug：
                 * 当 doneCount 已经是满额、但今天的完成记录缺失时（比如跨天遗留、
                 * 记录没写成功），早退会让状态永远停在"未完成"，拖到 100% 也不变绿。
                 * 现在无论次数是否变化都写一次记录，保证状态一致。
                 */
                // v3.34.21：先读旧记录再改字段，别再整条重写 —— 那样会把
                // 多时刻任务已确认的 t 条目和清单勾选一起冲掉
                CheckIn.update(this@MainActivity, dao, fresh) { rec ->
                    rec.copy(
                        doneCount = d,
                        completed = if (fresh.isMultiTime) rec.completed else d >= t
                    )
                }.completed
            }
            // 先刷新界面，再决定要不要停铃
            loadTasks()
            if (reached == true && !wasDone) {
                RingService.stop(this@MainActivity)
                toast("「${task.name}」已完成，今天不会再提醒")
            }
        }
    }

    /* ---------------- 历史页多选删除（v3.34.142） ---------------- */

    private fun setManage(on: Boolean) {
        manageMode = on
        adapter.setManageMode(on)
        findViewById<TextView>(R.id.btnManage).text =
            getString(if (on) R.string.btn_manage_done else R.string.btn_manage)
        renderManageBar()
    }

    private fun renderManageBar() {
        val bar = findViewById<View>(R.id.barHistoryActions)
        bar.visibility =
            if (manageMode && tab == TAB_HISTORY) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.tvSelectedCount).text =
            getString(R.string.history_selected_fmt, adapter.selectedIds.size)
    }

    private fun confirmDeleteSelected() {
        val ids = adapter.selectedIds.toSet()
        if (ids.isEmpty()) {
            toast(getString(R.string.history_select_none))
            return
        }
        ConfirmSheet(
            this,
            title = getString(R.string.history_delete_title),
            message = getString(R.string.history_delete_msg, ids.size),
            confirmText = getString(R.string.history_delete),
            onConfirm = { deleteSelected(ids) }
        )
    }

    private fun deleteSelected(ids: Set<Long>) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val dao = AppDatabase.get(this@MainActivity).taskDao()
                latestTasks.filter { it.id in ids }.forEach { t ->
                    dao.delete(t)
                    MiLinkBridge.removeFromCalendar(this@MainActivity, t.id)
                }
            }
            ids.forEach { AlarmScheduler.cancel(this@MainActivity, it) }
            setManage(false)
            loadTasks()
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}

/** 时间轴上的一条任务：开始→目标（分钟），done 决定条的颜色 */
data class TimelineTask(val name: String, val startMin: Int, val targetMin: Int, val done: Boolean)

/**
 * 时间图整列：lo/hi 是所有任务铺开后的起止分钟，条按比例落位；
 * nowMin = 选中日为今天时的当前分钟（画「现在」参考线），非今天为 -1。
 */
data class TimelineRow(
    val lo: Int,
    val hi: Int,
    val tasks: List<TimelineTask>,
    val nowMin: Int = -1,
    /** 日程头左上的日期（「10 月 27 日 · 星期二」，v3.34.80 参考稿 agenda-head） */
    val dateLabel: String = ""
)

// 页签编码以 TaskListBuilder 为唯一来源（列表筛选逻辑也在那里，避免两处各写一套）
private const val TAB_TODAY = TaskListBuilder.TAB_TODAY
private const val TAB_PLAN = TaskListBuilder.TAB_PLAN
private const val TAB_DONE = TaskListBuilder.TAB_DONE
private const val TAB_ALL = TaskListBuilder.TAB_ALL
private const val TAB_HISTORY = TaskListBuilder.TAB_HISTORY

/* 月历翻页窗口（v3.34.12）：2024-01 起共 144 个月（到 2035-12）。
   完成记录的查询起点随之拉宽到窗口首日——翻页预渲染的相邻月才有状态点数据 */
private const val CAL_PAGE_START_YEAR = 2024
private const val CAL_PAGE_MONTHS = 144

/* 触感反馈：记一组用轻震；到顶（做完）用系统确认震感（安卓 11+ 才有，低版本退回轻震）。
   注意震动不受「动画时长缩放」影响 —— 关了动画一样有手感。 */
private val buzzLight = HapticFeedbackConstants.VIRTUAL_KEY
private val buzzStrong =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM
    else HapticFeedbackConstants.VIRTUAL_KEY

/**
 * 一次「记一组」走多少格：
 *  - 设了「每组次数」→ 用它（悬挂抬腿 60 次、每组 10 次 → 一格 10 次）
 *  - 没设 → 按目标的 10% 走（生活类任务按百分比推进）
 */
internal fun stepOf(t: Task): Int =
    if (t.repsPerSet > 0) t.repsPerSet
    else ((t.targetCount + 9) / 10).coerceAtLeast(1)

/* ------------------------- 列表适配器 ------------------------- */

class TaskAdapter(
    private val onToggle: (Task) -> Unit,
    private val onEdit: (Task) -> Unit,
    private val onToggleGroup: (String) -> Unit,
    private val onSet: (Task, Int) -> Unit,
    private val onProgress: (Task, Int) -> Unit,
    private val onPickDay: (String) -> Unit,
    private val onMonthPage: (Int, Int) -> Unit,
    private val onPickMonth: () -> Unit,
    private val onToggleItem: (Task, Int) -> Unit,
    private val onOpenCheckIn: (Task) -> Unit,
    private val onToggleTime: (Task, Int) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    /** 列表行：一个分组一张外层卡（组头 + 组内任务），或无标签时的平铺任务 */
    sealed class Row {
        data class Group(val tag: String, val tasks: List<Task>, val collapsed: Boolean) : Row()
        data class Flat(val task: Task) : Row()

        /**
         * 月历（v3.34.12 起是 ViewPager2 的一页流）：tasks/records 是全量快照，
         * 每页的格子与状态点在绑定页时现算——相邻月预渲染也能拿到正确数据
         */
        data class Calendar(
            val year: Int,
            val month0: Int,
            val selected: String,
            val tasks: List<Task>,
            val records: Map<String, List<TaskRecord>>
        ) : Row()

        data class Timeline(val row: TimelineRow) : Row()

        /** 计划页空态兜底（这天没有安排 / 任务都在同一时刻） */
        data class Hint(val text: String) : Row()

        /**
         * 今日概览卡（v3.34.69，参考稿 dashboard 的 greeting 区）：
         * 问候 + 今日完成情况，红色渐变底压住整页玻璃的单调。
         */
        data class Overview(
            val hi: String,
            val sub: String,
            val progress: Float
        ) : Row()
    }

    private var rows: List<Row> = emptyList()
    private var doneField: Map<Long, Boolean> = emptyMap()

    /** 月历翻页适配器（月历行全列表只有一个，跨复用共享） */
    private var monthPager: MonthPagerAdapter? = null

    /** 历史页模式：卡片状态按「已完成 / 已过期」显示，meta 附原定日期 */
    private var historyMode = false

    /** 历史页管理模式（v3.34.142）：点卡片切换选中，配合底部全选 / 删除 */
    var manageMode = false
        private set
    val selectedIds = mutableSetOf<Long>()

    /** 选区变化后回调（活动页刷新计数条） */
    var onSelectionChanged: (() -> Unit)? = null

    fun setManageMode(on: Boolean) {
        manageMode = on
        selectedIds.clear()
        notifyDataSetChanged()
    }

    /** 点卡片切换选中 */
    fun toggleSelected(id: Long) {
        if (!selectedIds.add(id)) selectedIds.remove(id)
        notifyDataSetChanged()
        onSelectionChanged?.invoke()
    }

    /** 全选 / 再点一次全不选（按当前列表实际渲染的任务算，含折叠组里的） */
    fun selectAllVisible() {
        val ids = rows.flatMap {
            when (it) {
                is Row.Group -> it.tasks.map { t -> t.id }
                is Row.Flat -> listOf(it.task.id)
                else -> emptyList()
            }
        }
        if (ids.isNotEmpty() && ids.all { it in selectedIds }) selectedIds.removeAll(ids.toSet())
        else selectedIds.addAll(ids)
        notifyDataSetChanged()
        onSelectionChanged?.invoke()
    }

    /** 各任务今天已勾选的清单项下标（按天存，跨天自动重来） */
    private var itemChecks: Map<Long, Set<Int>> = emptyMap()

    /** 各任务清单里每项已做的组数（多组项用） */
    private var itemProgress: Map<Long, Map<Int, Int>> = emptyMap()

    /** 各任务今天已确认的时刻下标（v3.34.21 逐时刻完成态） */
    private var timeConfirms: Map<Long, Set<Int>> = emptyMap()

    fun setTimeConfirms(m: Map<Long, Set<Int>>) {
        timeConfirms = m
    }

    fun setItemProgress(m: Map<Long, Map<Int, Int>>) {
        itemProgress = m
    }

    /** 哪些任务的清单是展开的（只存在内存里，不落库） */
    private val expandedItems = mutableSetOf<Long>()

    fun setItemChecks(m: Map<Long, Set<Int>>) {
        itemChecks = m
    }

    private fun toggleItemsExpanded(taskId: Long) {
        if (!expandedItems.remove(taskId)) expandedItems.add(taskId)
        notifyDataSetChanged()
    }

    fun submit(
        tasks: List<Task>,
        doneMap: Map<Long, Boolean>,
        collapsed: Set<String>,
        history: Boolean = false,
        overview: Row.Overview? = null
    ) {
        doneField = doneMap
        historyMode = history
        val named = LinkedHashMap<String, MutableList<Task>>()
        val untagged = ArrayList<Task>()
        tasks.forEach { t ->
            val tag = t.tag.trim()
            if (tag.isEmpty()) untagged.add(t)
            else named.getOrPut(tag) { ArrayList() }.add(t)
        }

        val out = ArrayList<Row>()
        overview?.let { out.add(it) }
        if (named.isEmpty()) {
            // 一条标签都没有：保持平铺，不为分组而分组
            untagged.forEach { out.add(Row.Flat(it)) }
        } else {
            named.forEach { (tag, list) -> out.add(Row.Group(tag, list, tag in collapsed)) }
            if (untagged.isNotEmpty()) out.add(Row.Group("", untagged, "" in collapsed))
        }
        rows = out
        notifyDataSetChanged()
    }

    /**
     * 计划页：一张月历（ViewPager2 左右滑翻月）+ 选中那天的竖向时间图。
     * allTasks/records 是全量快照，月历每页的状态点由页适配器现算
     * （marks：0 无安排 / 1 已完成 / 2 没完成 / 3 未来待办）。
     * timeline 为 null（这天全是同刻任务/无任务）时显示一行 hint 兜底。
     */
    fun submitCalendar(
        year: Int,
        month0: Int,
        selected: String,
        allTasks: List<Task>,
        records: Map<String, List<TaskRecord>>,
        timeline: TimelineRow? = null,
        hint: String? = null
    ) {
        val out = ArrayList<Row>()
        out.add(Row.Calendar(year, month0, selected, allTasks, records))
        if (timeline != null) out.add(Row.Timeline(timeline))
        if (timeline == null && hint != null) out.add(Row.Hint(hint))
        rows = out
        notifyDataSetChanged()
    }

    class CalVH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.tvCalTitle)
        val prevMonth: View = v.findViewById(R.id.btnPrevMonth)
        val nextMonth: View = v.findViewById(R.id.btnNextMonth)
        val weekHead: LinearLayout = v.findViewById(R.id.rowWeekHead)
        val pager: androidx.viewpager2.widget.ViewPager2 = v.findViewById(R.id.vpMonths)
    }

    /**
     * 月历翻页适配器（v3.34.12）：一页一个月，窗口 2024-01 起 CAL_PAGE_MONTHS 个月。
     * 格子与状态点在绑定页时现算——不管翻到哪个月、预渲染的相邻页，数据都是最新的
     * （旧实现只算当前显示月，翻月必须等整页重载，是"卡卡的"的根源之一）。
     */
    private inner class MonthPagerAdapter(
        private val onPickDay: (String) -> Unit
    ) : RecyclerView.Adapter<MonthPageVH>() {

        var tasks: List<Task> = emptyList()
        var records: Map<String, List<TaskRecord>> = emptyMap()
        var selected: String = ""
        var today: String = ""

        fun posOf(year: Int, month0: Int): Int =
            ((year - CAL_PAGE_START_YEAR) * 12 + month0)
                .coerceIn(0, CAL_PAGE_MONTHS - 1)

        private fun monthOf(pos: Int): Pair<Int, Int> =
            (CAL_PAGE_START_YEAR + pos / 12) to (pos % 12)

        override fun onCreateViewHolder(
            parent: android.view.ViewGroup,
            viewType: Int
        ): MonthPageVH {
            val grid = GridLayout(parent.context).apply {
                columnCount = 7
                rowCount = 6
                layoutParams = android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
            return MonthPageVH(grid)
        }

        override fun getItemCount(): Int = CAL_PAGE_MONTHS

        override fun onBindViewHolder(holder: MonthPageVH, position: Int) {
            val (year, month0) = monthOf(position)
            val cg = com.norbury.taktu.util.CalendarGrid
            val cells = cg.cells(year, month0)

            // 每格状态点：1 全做完（绿环）/ 2 过去了还没做完（红弧=完成度）/ 3 未来安排（淡环）
            val marks = HashMap<String, Int>()
            // 各格完成度 0..1，进度环的弧长 = 这个比例（与 mark 同源，不另查库）
            val ratios = HashMap<String, Float>()
            cells.forEach { c ->
                if (!c.inMonth) return@forEach
                val list = cg.tasksOn(tasks, c.dateKey)
                if (list.isEmpty()) return@forEach
                val done = records[c.dateKey]?.count { it.completed } ?: 0
                ratios[c.dateKey] = done.toFloat() / list.size
                marks[c.dateKey] = when {
                    done >= list.size -> 1
                    c.dateKey < today -> 2
                    else -> 3
                }
            }

            // 换月（而非同月数据刷新）才播格子弹入动画：逐个错峰淡入 + 轻微上浮，
            // 快速划页/标题跳月时能看到"新月份浮现"的过渡（用户 2026-10-21）
            val monthKey = year * 12 + month0
            val animate = holder.boundMonth != monthKey
            holder.boundMonth = monthKey
            val d = holder.grid.context.resources.displayMetrics.density
            val rise = (5 * d).toInt()

            holder.grid.removeAllViews()
            var idx = 0
            cells.forEach { cell ->
                val v = buildDayCell(
                    holder.grid.context,
                    cell,
                    marks[cell.dateKey] ?: 0,
                    ratios[cell.dateKey] ?: 0f,
                    cell.dateKey == selected,
                    cell.dateKey == today
                )
                if (animate && cell.inMonth) {
                    v.alpha = 0f
                    v.translationY = rise.toFloat()
                    v.animate().alpha(1f).translationY(0f)
                        .setStartDelay((idx * 8).toLong())
                        .setDuration(160L)
                        .start()
                    idx++
                }
                holder.grid.addView(v)
            }
        }
    }

    class MonthPageVH(val grid: GridLayout) : RecyclerView.ViewHolder(grid) {
        /** 上次绑定的月份（year*12+month0）：换月才播格子弹入动画，同月数据刷新不重播 */
        var boundMonth: Int = Int.MIN_VALUE
    }

    class TimelineVH(v: View) : RecyclerView.ViewHolder(v) {
        val date: TextView = v.findViewById(R.id.tvAgendaDate)
        val range: TextView = v.findViewById(R.id.tvTimelineRange)
        val list: LinearLayout = v.findViewById(R.id.boxAgendaList)
    }

    class OverviewVH(v: View) : RecyclerView.ViewHolder(v) {
        val hi: TextView = v.findViewById(R.id.tvOverviewHi)
        val sub: TextView = v.findViewById(R.id.tvOverviewSub)
        val fill: View = v.findViewById(R.id.ovFill)
        val spacer: View = v.findViewById(R.id.ovSpacer)
        val control: com.google.android.material.materialswitch.MaterialSwitch =
            v.findViewById(R.id.ovControl)
    }

    class HintVH(v: View) : RecyclerView.ViewHolder(v) {
        val text: TextView = v.findViewById(R.id.tvHint)
    }

    class TaskVH(v: View) : RecyclerView.ViewHolder(v) {
        val bar: View = v.findViewById(R.id.barPriority)
        val rowItems: View = v.findViewById(R.id.rowItems)
        val rowTimes: LinearLayout = v.findViewById(R.id.rowTimes)
        val rowItemsHead: View = v.findViewById(R.id.rowItemsHead)
        val itemsChevron: ImageView = v.findViewById(R.id.ivItemsChevron)
        val itemProgress: TextView = v.findViewById(R.id.tvItemProgress)
        val itemList: LinearLayout = v.findViewById(R.id.boxItemList)
        val name: TextView = v.findViewById(R.id.tvTaskName)
        val meta: TextView = v.findViewById(R.id.tvTaskMeta)
        val status: TextView = v.findViewById(R.id.tvTaskStatus)
        val btnToggle: ImageButton = v.findViewById(R.id.btnToggle)
        val btnEdit: View = v.findViewById(R.id.btnEdit)
        val footerDivider: View = v.findViewById(R.id.footerDivider)
        val boxProgress: View = v.findViewById(R.id.boxProgress)
        val blocks: LinearLayout = v.findViewById(R.id.rowProgressBlocks)
        val setCaption: TextView = v.findViewById(R.id.tvSetCaption)
        val setInfo: TextView = v.findViewById(R.id.tvSetInfo)
        val btnMinus: com.google.android.material.button.MaterialButton =
            v.findViewById(R.id.btnMinus)
        val btnPlus: com.google.android.material.button.MaterialButton =
            v.findViewById(R.id.btnPlus)
    }

    class GroupVH(v: View) : RecyclerView.ViewHolder(v) {
        val header: View = v.findViewById(R.id.rowHeader)
        val name: TextView = v.findViewById(R.id.tvTagName)
        val count: TextView = v.findViewById(R.id.tvTagCount)
        val chevron: ImageView = v.findViewById(R.id.ivChevron)
        val dot: View = v.findViewById(R.id.dotAccent)
        val container: LinearLayout = v.findViewById(R.id.groupContainer)
    }

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is Row.Group -> TYPE_GROUP
        is Row.Calendar -> TYPE_CALENDAR
        is Row.Timeline -> TYPE_TIMELINE
        is Row.Hint -> TYPE_HINT
        is Row.Overview -> TYPE_OVERVIEW
        else -> TYPE_TASK
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_GROUP -> GroupVH(inf.inflate(R.layout.item_group, parent, false))
            TYPE_CALENDAR -> CalVH(inf.inflate(R.layout.item_calendar, parent, false))
            TYPE_TIMELINE -> TimelineVH(inf.inflate(R.layout.item_timeline, parent, false))
            TYPE_HINT -> HintVH(inf.inflate(R.layout.item_hint, parent, false))
            TYPE_OVERVIEW -> OverviewVH(inf.inflate(R.layout.item_overview, parent, false))
            else -> {
            val v = inf.inflate(R.layout.item_task, parent, false)
            // 卡片里的按钮在页面 setContentView 之后才创建，居中矫正在这里补做
            ButtonTuner.tune(v)
            // 轻量按压反馈：轻微缩放（提示词第 9 节，不做夸张动画）
            v.setOnTouchListener { view, ev ->
                when (ev.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN ->
                        view.animate().scaleX(0.98f).scaleY(0.98f).setDuration(70).start()
                    android.view.MotionEvent.ACTION_UP,
                    android.view.MotionEvent.ACTION_CANCEL ->
                        view.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                }
                false   // 不拦截，点击照常分发
            }
            TaskVH(v)
            }
        }
    }

    override fun getItemCount(): Int = rows.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Group -> bindGroup(holder as GroupVH, row)
            is Row.Flat -> bindTask(holder as TaskVH, row.task)
            is Row.Calendar -> bindCalendar(holder as CalVH, row)
            is Row.Timeline -> bindTimeline(holder as TimelineVH, row)
            is Row.Hint -> (holder as HintVH).text.text = row.text
            is Row.Overview -> (holder as OverviewVH).let { h ->
                h.hi.text = row.hi
                h.sub.text = row.sub
                // 完成度条：fill/spacer 按 完成数占比 分 weight（都是 LinearLayout 直属子级）。
                // 就地改 weight 不会触发重新测量，绑定时条会停在旧值 —— 统计页同 bug 在
                // v3.34.86 已修，这里也得走 layoutParams setter（用户 2026-11-06 实测条不满）
                val p = row.progress.coerceIn(0f, 1f)
                (h.fill.layoutParams as LinearLayout.LayoutParams).let {
                    it.weight = p
                    h.fill.layoutParams = it
                }
                (h.spacer.layoutParams as LinearLayout.LayoutParams).let {
                    it.weight = 1f - p
                    h.spacer.layoutParams = it
                }
                // 外部控制开关：与设置页同一套 Prefs + 前台服务
                val ctx = h.itemView.context
                h.control.setOnCheckedChangeListener(null)
                h.control.isChecked = Prefs.controlEnabled(ctx)
                h.control.setOnCheckedChangeListener { _, checked ->
                    if (checked == Prefs.controlEnabled(ctx)) return@setOnCheckedChangeListener
                    Prefs.setControlEnabled(ctx, checked)
                    if (checked) ControlService.start(ctx) else ControlService.stop(ctx)
                }
            }
        }
    }


    /** 月历：ViewPager2 左右滑翻月（v3.34.12，跟手拖动带结算动画）+ 每页 42 格 + 状态点 */
    private fun bindCalendar(holder: CalVH, row: Row.Calendar) {
        val ctx = holder.itemView.context
        holder.title.text =
            com.norbury.taktu.util.CalendarGrid.title(row.year, row.month0)
        // 点月份标题直接弹年月选择，跨多月不用一格一格滑；
        // 左右玻璃圆钮翻月（v3.34.67，参考稿 month-switch），带滑动动画
        holder.title.setOnClickListener { onPickMonth() }
        holder.prevMonth.setOnClickListener {
            val p = holder.pager.currentItem
            if (p > 0) holder.pager.setCurrentItem(p - 1, true)
        }
        holder.nextMonth.setOnClickListener {
            val p = holder.pager.currentItem
            if (p < (monthPager?.itemCount ?: 0) - 1) holder.pager.setCurrentItem(p + 1, true)
        }

        // 网格高度按当月实际周数收（v3.34.82）：5 周的月只占 5 行。
        // 之前固定 6 行 × 46dp，月底那一整行空白把图例顶得老远（用户：留白太多）
        run {
            val cal = java.util.Calendar.getInstance().apply { set(row.year, row.month0, 1) }
            val firstDow = cal.get(java.util.Calendar.DAY_OF_WEEK) - 1
            val days = cal.getActualMaximum(java.util.Calendar.DAY_OF_MONTH)
            val rows = ((firstDow + days + 6) / 7).coerceIn(5, 6)
            val d = ctx.resources.displayMetrics.density
            val wantH = (rows * 46 * d).toInt()
            if (holder.pager.layoutParams.height != wantH) {
                holder.pager.layoutParams.height = wantH
                holder.pager.requestLayout()
            }
        }

        // 星期表头（只建一次）
        if (holder.weekHead.childCount == 0) {
            listOf(
                R.string.week_sun, R.string.week_mon, R.string.week_tue,
                R.string.week_wed, R.string.week_thu, R.string.week_fri, R.string.week_sat
            ).forEach { res ->
                val tv = TextView(ctx).apply {
                    text = ctx.getString(res)
                    textSize = 11.5f
                    gravity = android.view.Gravity.CENTER
                    setTextColor(ContextCompat.getColor(ctx, R.color.app_text_sub))
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                }
                holder.weekHead.addView(
                    tv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                )
            }
        }

        // 翻页适配器：adapter 级持有（月历行全列表只有一个），每次绑定向它同步最新数据
        val pa = monthPager ?: MonthPagerAdapter(onPickDay).also {
            monthPager = it
        }
        pa.tasks = row.tasks
        pa.records = row.records
        pa.selected = row.selected
        pa.today = AppDatabase.todayKey()
        pa.notifyDataSetChanged()
        if (holder.pager.adapter !== pa) holder.pager.adapter = pa

        // 翻页落定 → 把新月份写回并刷新（标题/时间图跟着换数据）；
        // 同月重复回调直接被 swipeCalendarMonth 的判等挡掉
        if (holder.pager.tag == null) {
            holder.pager.tag = true
            holder.pager.registerOnPageChangeCallback(
                object : androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
                    override fun onPageSelected(position: Int) {
                        onMonthPage(CAL_PAGE_START_YEAR + position / 12, position % 12)
                    }
                }
            )
            // 翻页过渡（用户 2026-10-21 要求"切换效果"）：拖动中相邻页淡出 +
            // 轻微缩放，拖到哪跟到哪，比落定后才播动画更跟手
            holder.pager.setPageTransformer { page, pos ->
                val f = minOf(kotlin.math.abs(pos), 1f)
                page.alpha = 1f - f * 0.35f
                val s = 1f - f * 0.04f
                page.scaleX = s
                page.scaleY = s
            }
        }

        // 选中月与当前页对齐（滑页回调已把月份写回，这里多为 no-op；跳页不动画）
        val target = pa.posOf(row.year, row.month0)
        if (holder.pager.currentItem != target) holder.pager.setCurrentItem(target, false)
    }

    /** 圆形底：fill=0 表示不填充；strokeW>0 时描边 */
    private fun oval(fill: Int, strokeW: Int = 0, strokeColor: Int = 0) =
        android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            if (fill != 0) setColor(fill)
            if (strokeW > 0) setStroke(strokeW, strokeColor)
        }

    /** 一格：日期数字；有安排的日子画「完成度环」（参考稿 mobile-ui-replica_red） */
    private fun buildDayCell(
        ctx: android.content.Context,
        cell: com.norbury.taktu.util.CalendarGrid.Cell,
        mark: Int,
        ratio: Float,
        selected: Boolean,
        isToday: Boolean
    ): View {
        val d = ctx.resources.displayMetrics.density

        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
        }
        /*
         * 注意参数顺序是 (rowSpec, colSpec)。
         * 之前把权重写给了第一个参数（行），列没有权重，配合 width=0
         * 会让整列宽度变成 0 —— 日历就是这么变成一片空白的。
         */
        box.layoutParams = GridLayout.LayoutParams(
            GridLayout.spec(GridLayout.UNDEFINED),
            GridLayout.spec(GridLayout.UNDEFINED, 1f)
        ).apply {
            width = 0
            height = (46 * d).toInt()
        }

        /*
         * 上下月的补格：只占位撑住网格形状，不显示日期、不给底色、不响应点击。
         * 一个月只出现一个「1 号」—— 要看 11 月的 1 号，翻月过去选。
         */
        if (!cell.inMonth) return box

        val accent = ContextCompat.getColor(ctx, R.color.accent)
        val accentOn = ContextCompat.getColor(ctx, R.color.accent_on)
        val onSurface = ContextCompat.getColor(ctx, R.color.app_text)

        val size = (32 * d).toInt()
        val num = TextView(ctx).apply {
            text = cell.day.toString()
            textSize = 14f
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(size, size)
            val okBg = ContextCompat.getColor(ctx, R.color.ok_container)
            val okFg = ContextCompat.getColor(ctx, R.color.ok_on_container)
            val planBg = ContextCompat.getColor(ctx, R.color.accent_container)
            val planFg = ContextCompat.getColor(ctx, R.color.accent_on_container)
            val okC = ContextCompat.getColor(ctx, R.color.ok)
            val line = ContextCompat.getColor(ctx, R.color.app_stroke)
            val sub = ContextCompat.getColor(ctx, R.color.app_text_sub)

            // 有安排的日子画完成度环：弧长 = 当天完成比例（0 = 只剩淡底环）
            background = when {
                selected -> oval(accent)
                isToday -> oval(0, (1.5 * d).toInt(), accent)
                mark == 1 -> RingDrawable(1f, okBg, okC, (2 * d).toInt())   // 全做完：满环绿
                mark == 2 -> RingDrawable(ratio, planBg, accent, (2 * d).toInt()) // 过去：红弧=完成度
                mark == 3 -> RingDrawable(0f, line, accent, (2 * d).toInt()) // 未来：淡环
                else -> null
            }
            setTextColor(
                when {
                    selected -> accentOn
                    isToday -> accent
                    mark == 1 -> okFg
                    mark == 2 -> planFg
                    mark == 3 -> sub
                    else -> onSurface
                }
            )
        }
        box.addView(num)

        box.setOnClickListener { onPickDay(cell.dateKey) }
        return box
    }

    /**
     * 计划页日程（v3.34.78，dark-task-cards.html 的 agenda 行式布局）：
     * 竖向甘特换成「时刻 | 轴线 | 卡片」的列表 —— 完整任务名不再挤在泳道列头里。
     * 数据源不变（TimelineRow/TimelineTask 原样），只换呈现；
     * 范围 pill 保留（用户 2026-10-27：主要看布局，颜色先不用）。
     */
    private fun bindTimeline(holder: TimelineVH, row: Row.Timeline) {
        val ctx = holder.itemView.context
        holder.date.text = row.row.dateLabel
        holder.date.visibility = if (row.row.dateLabel.isEmpty()) View.GONE else View.VISIBLE
        holder.range.text = ctx.getString(
            R.string.timeline_range_fmt,
            Task.formatMin(row.row.lo), Task.formatMin(row.row.hi)
        )
        holder.list.removeAllViews()
        row.row.tasks.forEach { tt ->
            holder.list.addView(agendaRow(ctx, tt))
        }
    }


    /** 一行日程：左开始时刻 · 中间细轴线 · 右侧圆角卡（标题 + 目标时刻 · 状态） */
    private fun agendaRow(ctx: android.content.Context, tt: TimelineTask): View {
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val time = TextView(ctx).apply {
            text = Task.formatMin(tt.startMin)
            textSize = 11.5f
            includeFontPadding = false
            setTextColor(ContextCompat.getColor(ctx, R.color.app_text_sub))
            layoutParams = LinearLayout.LayoutParams(dp(40), LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { gravity = Gravity.CENTER_VERTICAL }
        }

        val axis = View(ctx).apply {
            setBackgroundColor(ContextCompat.getColor(ctx, R.color.app_stroke))
            layoutParams = LinearLayout.LayoutParams(dp(1), LinearLayout.LayoutParams.MATCH_PARENT)
                .apply { marginStart = dp(6); marginEnd = dp(10) }
        }

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 12 * d
                setColor(ContextCompat.getColor(ctx, R.color.app_card))
                setStroke(dp(1), ContextCompat.getColor(ctx, R.color.app_stroke))
            }
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

            addView(TextView(ctx).apply {
                text = tt.name
                textSize = 13.5f
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                paint.isStrikeThruText = tt.done
                setTextColor(
                    ContextCompat.getColor(ctx, if (tt.done) R.color.app_text_sub else R.color.app_text)
                )
            })

            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(4) }

                // 左侧目标时刻（纯时间点任务没有，放个占位把状态顶到右边）
                addView(TextView(ctx).apply {
                    text = if (tt.targetMin > tt.startMin)
                        ctx.getString(R.string.agenda_target_fmt, Task.formatMin(tt.targetMin))
                    else ""
                    textSize = 11f
                    setTextColor(ContextCompat.getColor(ctx, R.color.app_text_sub))
                    layoutParams = LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                    )
                })
                addView(TextView(ctx).apply {
                    text = ctx.getString(if (tt.done) R.string.status_done else R.string.agenda_pending)
                    textSize = 11f
                    setTextColor(
                        ContextCompat.getColor(ctx, if (tt.done) R.color.ok else R.color.app_text_sub)
                    )
                })
            })
        }

        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
            addView(time)
            addView(axis)
            addView(card)
        }
    }

    private fun bindGroup(holder: GroupVH, row: Row.Group) {
        val ctx = holder.name.context
        holder.name.text =
            if (row.tag.isEmpty()) ctx.getString(R.string.group_untagged) else row.tag
        holder.count.text = ctx.getString(R.string.group_count_fmt, row.tasks.size)
        // 展开朝上，折叠朝下
        holder.chevron.rotation = if (row.collapsed) 0f else 180f

        // 组头小圆点：同一标签固定同一颜色，扫一眼就能对上
        val attr = if (row.tag.isEmpty()) {
            com.google.android.material.R.attr.colorOnSurfaceVariant
        } else {
            accentAttr(row.tag)
        }
        holder.dot.background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(com.google.android.material.color.MaterialColors.getColor(holder.dot, attr))
        }

        // 只有组头可点：以前绑在 itemView 上，点组内卡片空隙 / 下边缘都会误折叠
        holder.header.setOnClickListener { onToggleGroup(row.tag) }

        holder.container.removeAllViews()
        if (row.collapsed) return
        val inf = LayoutInflater.from(ctx)
        row.tasks.forEach { t ->
            val v = inf.inflate(R.layout.item_task, holder.container, false)
            styleNested(v)
            holder.container.addView(v)
            bindTask(TaskVH(v), t)
        }
    }

    /**
     * 组内任务卡：圆角收小、间距收紧。
     *
     * 底色不再在这里改 —— 之前用 colorSurfaceContainerHighest 做层次，
     * 结果浅色模式下那是一块**灰色**（#E6E1E5），卡片又灰又脏。
     * 现在卡片色统一由 XML 给（暖白），层次靠圆角 + 细描边 + 间距体现。
     */
    private fun styleNested(v: View) {
        val card = v as com.google.android.material.card.MaterialCardView
        val d = v.resources.displayMetrics.density
        card.shapeAppearanceModel = card.shapeAppearanceModel.toBuilder()
            .setAllCornerSizes(18 * d)
            .build()
        card.cardElevation = 0f
        (v.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
            lp.bottomMargin = (10 * d).toInt()
            v.layoutParams = lp
        }
    }

    /** 重复规则的短描述（卡片信息行用） */
    private fun repeatShort(t: Task): String = RepeatRule.repeatShortLabel(t)

    /** 组头圆点的取色：同一标签恒定（注意 colorPrimary 是框架属性，走 android.R） */
    private fun accentAttr(tag: String): Int =
        when (kotlin.math.abs(tag.hashCode()) % 3) {
            0 -> android.R.attr.colorPrimary
            1 -> com.google.android.material.R.attr.colorTertiary
            else -> com.google.android.material.R.attr.colorSecondary
        }

    private fun bindTask(holder: TaskVH, t: Task) {
        val isDone = doneField[t.id] == true
        // 当天是不是法定休息日，决定了用哪一套时间
        val todayKey = AppDatabase.todayKey()

        holder.name.text = t.name
        holder.name.alpha = 1f
        // 不再用删除线表示完成 —— 删除线压在中文标题上很难看。
        // 状态改用整张卡片的底色表达，见下方「卡片状态着色」。

        // 信息分两行：第一行"进度 · 截止 · 重复"，第二行"催促 / 复查"
        // ── 信息分两行：第一行"组数 · 次数 · 截止"，第二行"重复 / 催促 / 复查" ──
        val setMode = t.repsPerSet > 0 && t.targetCount > 0
        val reps = if (setMode) t.repsPerSet.coerceAtLeast(1) else 0
        val totalSets = if (setMode) (t.targetCount + reps - 1) / reps else 0
        val doneSets = if (setMode) (t.doneCount / reps).coerceIn(0, totalSets) else 0
        val finished = setMode && doneSets >= totalSets

        /*
         * 信息压成一行（放不下自动折两行）：次数 · 目标 · 重复 · 始 · 止。
         * 「始」= 开始时间（催促）；「止」= 截止时间（复查改名后的卡片缩写）。
         * 原来「催促 / 复查」单独占一行，卡片太高 —— 三个任务就得滑屏。
         * 分组任务不在这里重复组数，组数由下面的 tvSetInfo 显示。
         *
         * v3.34.21：多时刻任务**不再在这里显示首时刻的始/止**（那是误导 ——
         * 每个时刻各有自己的开始/复查，逐行显示在 rowTimes 里）。
         */
        holder.meta.text = buildString {
            if (t.targetCount > 0) append("${t.doneCount}/${t.targetCount} · ")
            // 历史页显示的是任务**原定日**的时间：若按今天是不是休息日来选
            // 另一套时间（deadlineOn），原定日与今天类型不同就会显示错
            if (historyMode) {
                append(Task.formatMin(t.deadlineMin)).append(" 目标")
                if (!t.isMultiTime) {
                    if (t.preOffsetMin >= 0) {
                        append(" · 始 ").append(Task.formatMin(t.preAlertMin))
                    }
                    if (t.postOffsetMin >= 0) {
                        append(" · 止 ").append(Task.formatMin(t.finalCheckMin))
                    }
                }
                val ref = t.endDate.ifBlank { t.anchorDate }
                if (ref.isNotBlank()) append(" · ").append(ref)
            } else if (t.isMultiTime) {
                val times = t.timesOn(todayKey)
                val doneCount = times.indices.count { timeConfirms[t.id]?.contains(it) == true }
                append(times.size).append(" 个时刻")
                if (doneCount > 0) append(" · 已确认 ").append(doneCount).append('/').append(times.size)
            } else {
                append(Task.formatMin(t.deadlineOn(todayKey))).append(" 目标")
                if (t.preOffsetMin >= 0) {
                    append(" · 始 ").append(Task.formatMin(t.preAlertOn(todayKey)))
                }
                if (t.postOffsetMin >= 0) {
                    append(" · 止 ").append(Task.formatMin(t.finalCheckOn(todayKey)))
                }
            }
            append(" · ").append(repeatShort(t))
        }
        renderTimes(holder, t, todayKey)

        // ── 状态 ──
        val now = Calendar.getInstance()
        val nowMin = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val todayMatch = RepeatRule.shouldTriggerTask(
            t, AppDatabase.todayKey(), now.get(Calendar.DAY_OF_WEEK)
        )

        val statusText: String
        val pillKind: Ui.Pill
        when {
            isDone -> {
                statusText = holder.itemView.context.getString(R.string.status_done)
                pillKind = Ui.Pill.DONE
            }
            !t.enabled -> {
                statusText = holder.itemView.context.getString(R.string.status_disabled)
                pillKind = Ui.Pill.NEUTRAL
            }
            // 历史页：没做完就是过期了，不显示「今天不用做」这种无意义文案
            historyMode && !isDone -> {
                statusText = holder.itemView.context.getString(R.string.status_expired)
                pillKind = Ui.Pill.OVERDUE
            }
            !todayMatch -> {
                statusText = holder.itemView.context.getString(R.string.status_off_day)
                pillKind = Ui.Pill.NEUTRAL
            }
            // 复查已过 → 逾期。postOffsetMin = -1（关闭）时不能直接比 finalCheckOn：
            // 它会等于截止点前一分，导致截止前一分钟就假逾期
            //
            // v3.34.22：多时刻任务算的是「当前该关注的那一个时刻」，
            // 不是首个时刻 —— 上午那次做完、下午那次还没到时不该显示过期。
            // 口径见 data/TaskStatus.kt。
            else -> {
                val st = TaskStatus.kind(
                    t,
                    timeConfirms[t.id] ?: emptySet(),
                    todayKey,
                    nowMin
                )
                when (st) {
                    TaskStatus.Kind.OVERDUE -> {
                        statusText = holder.itemView.context.getString(R.string.status_overdue)
                        pillKind = Ui.Pill.OVERDUE
                    }
                    TaskStatus.Kind.URGING -> {
                        statusText = holder.itemView.context.getString(R.string.status_urging)
                        pillKind = Ui.Pill.URGING
                    }
                    TaskStatus.Kind.PAST_DEADLINE -> {
                        statusText = holder.itemView.context.getString(R.string.status_past_deadline)
                        pillKind = Ui.Pill.OVERDUE
                    }
                    TaskStatus.Kind.WAITING -> {
                        statusText = holder.itemView.context.getString(R.string.status_waiting)
                        pillKind = Ui.Pill.NEUTRAL
                    }
                }
            }
        }
        holder.status.text = statusText
        holder.status.visibility = View.VISIBLE
        Ui.pill(holder.status, pillKind)
        // 点卡片进详情（v3.34.65：原「详情」按钮取消，入口上移到整卡；
        // 时刻行 / 清单行 / 滑杆 / 左侧按钮各自消费自己的点击，不会误触）
        // 管理模式（v3.34.142）下点卡片 = 切换选中，不进详情
        holder.itemView.setOnClickListener {
            if (manageMode) toggleSelected(t.id) else onOpenCheckIn(t)
        }
        bindPriorityBar(holder, t)
        renderItems(holder, t)

        /*
         * ── 卡片状态着色 ──
         *   已完成        → 淡绿底（一眼扫过就知道做完了）
         *   今天还没完成  → 淡红底（提醒你还有事没做）
         *   今天不用做    → 中性暖白（休息日 / 已停用，不该被标红）
         */
        val c = holder.itemView.context
        val card = holder.itemView as com.google.android.material.card.MaterialCardView
        val notToday = !t.enabled || !todayMatch
        val bgRes: Int
        val strokeRes: Int
        when {
            isDone -> {
                bgRes = R.color.card_done
                strokeRes = R.color.card_done_stroke
                holder.name.setTextColor(ContextCompat.getColor(c, R.color.ok_on_container))
            }
            notToday -> {
                bgRes = R.color.app_card
                strokeRes = R.color.app_stroke
                holder.name.setTextColor(ContextCompat.getColor(c, R.color.app_text))
            }
            else -> {
                bgRes = R.color.card_todo
                strokeRes = R.color.card_todo_stroke
                holder.name.setTextColor(ContextCompat.getColor(c, R.color.app_text))
            }
        }
        card.setCardBackgroundColor(ContextCompat.getColor(c, bgRes))
        card.setStrokeColor(ContextCompat.getColor(c, strokeRes))

        // ── 管理模式（历史页多选删除，v3.34.142）──
        // 选中 = 主题色描边 + 整卡恢复亮度；未选中整卡压暗。
        // 操作区（进度 / 时刻 / 清单）全部藏掉，防止点卡片选中的同时误触打卡控件
        if (manageMode) {
            val on = t.id in selectedIds
            card.strokeWidth = (2 * c.resources.displayMetrics.density).toInt()
            card.setStrokeColor(
                ContextCompat.getColor(c, if (on) R.color.accent else strokeRes)
            )
            holder.itemView.alpha = if (on) 1f else 0.45f
            holder.boxProgress.visibility = View.GONE
            holder.btnToggle.visibility = View.GONE
            holder.rowTimes.visibility = View.GONE
            holder.rowItems.visibility = View.GONE
            holder.footerDivider.visibility = View.GONE
        } else {
            holder.itemView.alpha = 1f
        }

        // ── 进度区：有目标次数 → 可拖动进度条；没有 → 「标记完成」按钮 ──
        val ctx = holder.itemView.context
        if (t.targetCount > 0) {
            holder.boxProgress.visibility = View.VISIBLE
            holder.btnToggle.visibility = View.GONE

            val target = t.targetCount
            val pct = (t.doneCount * 100 / target).coerceIn(0, 100)
            val bySet = setMode && totalSets > 0

            // 分组模式：一格 = 一组；百分比模式：一格 = 5%（生活类任务按百分比推进）
            val curStep = if (bySet) doneSets else pct

            fun label(step: Int, done: Int, p: Int): String =
                if (bySet) {
                    ctx.getString(
                        R.string.progress_sets_fmt,
                        step.coerceIn(0, totalSets), totalSets, p
                    )
                } else {
                    ctx.getString(
                        R.string.progress_count_fmt,
                        done.coerceIn(0, target), target, p
                    )
                }

            holder.setInfo.text = label(curStep, t.doneCount, pct)

            // ── 分块进度条（v3.34.79，参考稿 group-progress-blocks）──
            // 组模式一格 = 一组；百分比模式 20 格、一格 5%。点某格直接推进到那里，
            // 替代原拖动条（参考稿没有滑块，卡片上靠 −/＋ 与分块点亮表达进度）。
            val segs = if (bySet) totalSets.coerceAtMost(24) else 20
            val filled = if (bySet) doneSets
                         else Math.round(pct / 100f * segs)
            holder.blocks.removeAllViews()
            val d = ctx.resources.displayMetrics.density
            val segGap = (4 * d).toInt()
            val segH = (8 * d).toInt()
            for (i in 0 until segs) {
                val on = i < filled
                holder.blocks.addView(View(ctx).apply {
                    background = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = (4 * d).toFloat()
                        setColor(
                            ContextCompat.getColor(
                                ctx, if (on) R.color.accent else R.color.app_card_alt
                            )
                        )
                    }
                    layoutParams = LinearLayout.LayoutParams(0, segH, 1f).apply {
                        marginEnd = if (i < segs - 1) segGap else 0
                    }
                    setOnClickListener {
                        val done = if (bySet) ((i + 1) * reps).coerceAtMost(target)
                                   else (target * (i + 1) / segs).coerceAtMost(target)
                        onProgress(t, done)
                    }
                })
            }

            // 说明行：每组几次（参考稿 exercise-caption）
            if (bySet && t.repsPerSet > 0) {
                holder.setCaption.visibility = View.VISIBLE
                holder.setCaption.text =
                    ctx.getString(R.string.set_caption_fmt, t.repsPerSet)
            } else {
                holder.setCaption.visibility = View.GONE
            }

            holder.btnMinus.isEnabled = t.doneCount > 0
            holder.btnMinus.alpha = if (t.doneCount > 0) 1f else 0.4f
            holder.btnPlus.isEnabled = t.doneCount < target
            holder.btnPlus.alpha = if (t.doneCount < target) 1f else 0.4f
            holder.btnMinus.setOnClickListener { onSet(t, -1) }
            // 记一组给个手感：轻震；这一组到顶（做完了）换成更重的震
            holder.btnPlus.setOnClickListener { v ->
                v.performHapticFeedback(if (t.doneCount + stepOf(t) >= target) buzzStrong else buzzLight)
                onSet(t, +1)
            }
        } else {
            holder.boxProgress.visibility = View.GONE
            holder.btnToggle.visibility = View.VISIBLE

            // 圆钮（v3.34.81）：√ 保留，未完成 = 玻璃底 + 红勾；已完成 = 红底 + 白勾
            holder.btnToggle.background = ContextCompat.getDrawable(
                ctx, if (isDone) R.drawable.bg_circle_accent else R.drawable.bg_circle_glass
            )
            holder.btnToggle.imageTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(ctx, if (isDone) R.color.accent_on else R.color.accent)
            )
            holder.btnToggle.contentDescription =
                ctx.getString(if (isDone) R.string.undo_done else R.string.mark_done)
            // 触感：按下去的瞬间就震一下，不等落库
            holder.btnToggle.setOnTouchListener { v, e ->
                if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                    v.performHapticFeedback(buzzLight)
                }
                false
            }
            holder.btnToggle.setOnClickListener { v ->
                if (isDone) {
                    onToggle(t)
                } else {
                    // 完成特效（v3.34.81）：红底 + 两下回弹（「确认双击」的手感），
                    // 缩放只给到 1.12 —— 之前 1.35 会顶到卡片边被裁成方形
                    v.performHapticFeedback(buzzStrong)
                    v.background = ContextCompat.getDrawable(ctx, R.drawable.bg_circle_accent)
                    (v as? android.widget.ImageView)?.imageTintList =
                        android.content.res.ColorStateList.valueOf(
                            ContextCompat.getColor(ctx, R.color.accent_on)
                        )
                    v.animate().scaleX(1.12f).scaleY(1.12f).setDuration(70)
                        .withEndAction {
                            v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(60)
                                .withEndAction {
                                    v.animate().scaleX(1f).scaleY(1f).setDuration(80)
                                        .withEndAction { onToggle(t) }
                                        .start()
                                }
                                .start()
                        }
                        .start()
                }
            }
        }

        holder.btnEdit.setOnClickListener { onEdit(t) }
    }

    /**
     * 逐时刻行（v3.34.21）：多时刻任务每个时刻一行 ——
     * `✓ 11:25 · 始 10:55 · 止 12:25`，点整行勾 / 取消这一个时刻。
     *
     * 用户原话：「每个时刻应该分别对应自己的开始/复查时间，并分别记录完成状态，
     * 而不是一个时刻勾选就整体完成。」这行就是那句话的落点。
     * 单时刻任务不渲染（meta 里已经写了目标 / 始 / 止）。
     */
    private fun renderTimes(holder: TaskVH, t: Task, todayKey: String) {
        val ctx0 = holder.itemView.context
        val d0 = ctx0.resources.displayMetrics.density
        // footer 发丝线间距（v3.34.87）：多时刻卡末行下面就是这条线，
        // 要贴着末行（0dp）才和行间分隔线一致；普通卡恢复 8dp
        fun setDividerGap(dp: Int) {
            val lp = holder.footerDivider.layoutParams as LinearLayout.LayoutParams
            val want = (dp * d0).toInt()
            if (lp.topMargin != want) {
                lp.topMargin = want
                holder.footerDivider.layoutParams = lp
            }
        }
        if (!t.isMultiTime || historyMode) {
            holder.rowTimes.visibility = View.GONE
            setDividerGap(8)
            return
        }
        val times = t.timesOn(todayKey)
        if (times.isEmpty()) {
            holder.rowTimes.visibility = View.GONE
            setDividerGap(8)
            return
        }
        holder.rowTimes.visibility = View.VISIBLE
        holder.rowTimes.removeAllViews()
        setDividerGap(0)

        val ctx = ctx0
        val d = d0
        val done = timeConfirms[t.id] ?: emptySet()
        val ripple = ctx.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
            .let { a -> val dr = a.getDrawable(0); a.recycle(); dr }

        // slot 行（v3.34.79，参考稿 slot-list）：✓/○ 图标 · 时刻 · 右侧「已确认/待确认」，
        // 行间一条发丝线；整行可点，勾 / 取消这一个时刻。
        fun hairline() = View(ctx).apply {
            setBackgroundColor(ContextCompat.getColor(ctx, R.color.app_stroke))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (1 * d).toInt().coerceAtLeast(1)
            )
        }
        holder.rowTimes.addView(hairline())
        // 择一（v3.34.96）：确认任一个时刻即算完成 —— 其余时刻视为当天已关闭，不再显示
        val eitherDone = t.eitherMode && done.isNotEmpty()
        times.forEachIndexed { idx, timeMin ->
            val ok = idx in done
            if (eitherDone && !ok) return@forEachIndexed
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                background = ripple
                setPadding((4 * d).toInt(), 0, (4 * d).toInt(), 0)
                // v3.34.85：30→26dp，最后一行到卡片下沿线的距离再收紧
                minimumHeight = (26 * d).toInt()
                setOnClickListener { onToggleTime(t, idx) }
                isClickable = true
            }
            row.addView(TextView(ctx).apply {
                // 方框（v3.34.87）：□ 未确认 / ■ 已确认，与清单方框统一
                text = if (ok) "■" else "□"
                textSize = 12f
                includeFontPadding = false
                gravity = android.view.Gravity.CENTER
                setTextColor(
                    ContextCompat.getColor(ctx, if (ok) R.color.ok else R.color.app_text_sub)
                )
            }, LinearLayout.LayoutParams((18 * d).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT))
            row.addView(TextView(ctx).apply {
                text = Task.formatMin(timeMin)
                textSize = 11.5f
                includeFontPadding = false
                setTextColor(
                    ContextCompat.getColor(ctx, if (ok) R.color.app_text_sub else R.color.app_text)
                )
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = (8 * d).toInt() })
            row.addView(TextView(ctx).apply {
                text = ctx.getString(if (ok) R.string.slot_confirmed else R.string.slot_pending)
                textSize = 10.5f
                includeFontPadding = false
                setTextColor(
                    ContextCompat.getColor(ctx, if (ok) R.color.ok else R.color.app_text_sub)
                )
            })
            holder.rowTimes.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
            // 末行不加线：下面紧跟着卡片 footer 的发丝线，两条贴在一起会显成双线
            if (idx < times.lastIndex) holder.rowTimes.addView(hairline())
        }
    }

    /**
     * 清单渲染：标题行显示「清单 2/5」，点开才列出每一项。
     * 每项是纯文本（训练的"卧推 4×8-12"、采购的"牛奶 ×2"都直接写），勾完最后一项自动算任务完成。
     */
    private fun renderItems(holder: TaskVH, t: Task) {
        val specs = t.itemSpecs
        if (specs.isEmpty()) {
            holder.rowItems.visibility = View.GONE
            return
        }
        // 详情入口已上移到整卡点击（v3.34.65），这里不再需要单独的按钮

        holder.rowItems.visibility = View.VISIBLE
        val checked = itemChecks[t.id] ?: emptySet()
        val progress = itemProgress[t.id] ?: emptyMap()
        // 完成项数：多组项看组数是否做满，一次性项看有没有勾
        val finished = specs.indices.count { i ->
            val s = specs[i]
            val v = progress[i] ?: 0
            if (v > 0) v >= s.sets else (i in checked && s.sets <= 1)
        }
        val expanded = t.id in expandedItems
        holder.itemProgress.text = "清单 $finished/${specs.size}"
        // 箭头用真图标（展开时转 180°），不再是 ∨ / ∧ 文字
        holder.itemsChevron.rotation = if (expanded) 180f else 0f
        holder.rowItemsHead.setOnClickListener { toggleItemsExpanded(t.id) }

        holder.itemList.visibility = if (expanded) View.VISIBLE else View.GONE
        holder.itemList.removeAllViews()
        if (!expanded) return

        val ctx = holder.itemView.context
        val d = ctx.resources.displayMetrics.density
        val ripple = ctx.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
            .let { a -> val dr = a.getDrawable(0); a.recycle(); dr }
        specs.forEachIndexed { i, spec ->
            val multi = spec.sets > 1
            val v = progress[i] ?: 0
            val done = if (multi) v >= spec.sets else i in checked
            val suffix = when {
                !multi -> ""
                spec.reps > 0 -> "  ${spec.sets}×${spec.reps}"
                else -> "  ${spec.sets} 组"
            }
            val tv = TextView(ctx).apply {
                text = buildString {
                    // 方框（v3.34.81）：□ 未勾 / ■ 已勾，与卡片方角语言一致
                    append(if (done) "■ " else "□ ")
                    append(spec.name).append(suffix)
                    if (multi) append("　$v/${spec.sets}")
                }
                textSize = 13f
                setTextColor(
                    ContextCompat.getColor(
                        ctx, if (done) R.color.app_text_sub else R.color.app_text
                    )
                )
                if (done) {
                    paintFlags = paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
                }
                val p = (8 * d).toInt()
                setPadding(0, p, p, p)
                background = ripple
                // 多组项在卡片上不直接加减（要去任务界面），点了就进界面
                setOnClickListener { if (multi) onOpenCheckIn(t) else onToggleItem(t, i) }
            }
            holder.itemList.addView(tv, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }
    }

    /** 左边缘优先级色条：高=红、低=灰、中=不显示（默认档不挂标记） */
    private fun bindPriorityBar(holder: TaskVH, t: Task) {
        if (t.priority == Task.PRIORITY_MID) {
            holder.bar.visibility = View.GONE
            return
        }
        holder.bar.visibility = View.VISIBLE
        holder.bar.setBackgroundColor(
            ContextCompat.getColor(
                holder.itemView.context,
                if (t.priority == Task.PRIORITY_HIGH) R.color.danger else R.color.app_text_sub
            )
        )
    }

    companion object {
        private const val TYPE_TASK = 0
        private const val TYPE_GROUP = 1
        private const val TYPE_CALENDAR = 2
        private const val TYPE_TIMELINE = 3
        private const val TYPE_HINT = 4
        private const val TYPE_OVERVIEW = 5
    }

    /** 实心强调：陶土橘底 + 白字 */
    private fun com.google.android.material.button.MaterialButton.styleFilled() {
        backgroundTintList = android.content.res.ColorStateList.valueOf(
            ContextCompat.getColor(context, R.color.accent)
        )
        setTextColor(ContextCompat.getColor(context, R.color.accent_on))
        iconTint = android.content.res.ColorStateList.valueOf(
            ContextCompat.getColor(context, R.color.accent_on)
        )
        strokeWidth = 0
    }

    /** 弱化：透明底 + 细描边（已完成状态用它，降低强调度） */
    private fun com.google.android.material.button.MaterialButton.styleOutlined() {
        backgroundTintList =
            android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
        setTextColor(ContextCompat.getColor(context, R.color.app_text))
        iconTint = android.content.res.ColorStateList.valueOf(
            ContextCompat.getColor(context, R.color.app_text)
        )
        setStrokeColorResource(R.color.app_stroke)
        strokeWidth = (1 * resources.displayMetrics.density).toInt()
    }
}
