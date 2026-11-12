package com.norbury.taktu

import android.Manifest
import android.os.Bundle
import android.text.TextUtils
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import android.widget.ImageButton
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.norbury.taktu.alarm.AlarmScheduler
import com.norbury.taktu.data.AppDatabase
import com.norbury.taktu.data.ClockOffset
import com.norbury.taktu.data.RepeatRule
import com.norbury.taktu.data.Task
import com.norbury.taktu.data.TimeOffsets
import com.norbury.taktu.data.TimeSpec
import com.norbury.taktu.sheet.ClockSheet
import com.norbury.taktu.sheet.DateSheet
import com.norbury.taktu.sheet.DayPick
import com.norbury.taktu.sheet.WheelSheet
import com.norbury.taktu.sheet.TagSheet
import com.norbury.taktu.sheet.TextSheet
import com.norbury.taktu.sheet.TimeSheet
import com.norbury.taktu.sheet.IntervalConfigSheet
import com.norbury.taktu.sheet.IntervalSheet
import com.norbury.taktu.util.HolidayCalendar
import com.norbury.taktu.util.LunarCalendar
import com.norbury.taktu.util.MiLinkBridge
import com.norbury.taktu.util.Permissions
import com.norbury.taktu.util.Prefs
import com.norbury.taktu.util.Ui
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TaskEditActivity : BaseActivity() {

    companion object {
        const val EXTRA_TASK_ID = "task_id"

        // 每天时刻模式
        private const val TIME_FIXED = 0
        private const val TIME_INTERVAL = 1
    }

    private var taskId: Long = -1L
    private var deadlineMin = 20 * 60

    /** 法定休息日的截止时间；-1 = 与 [deadlineMin] 相同（仅旧单时刻模式有意义） */
    private var restDayDeadlineMin = -1
    private var preOffset = 30
    private var postOffset = 120
    private var repeatMask = RepeatRule.DAILY
    private var createdAt = System.currentTimeMillis()

    /** 法定节假日是否跳过（对「每天 / 自定义星期」生效） */
    private var skipHolidays = true

    /** 标签（首页分组的依据），空串表示未分组 */
    private var tag = ""

    /** 每组次数（逐组打卡 / 百分比进度），0 表示一次性勾选 */
    private var repsPerSet = 0

    /** 优先级：0=高 / 1=中 / 2=低，列表一律先按它排 */
    private var priority = Task.PRIORITY_MID

    /** 内置标签；自定义标签持久化在 Prefs，首页分组按字符串走，天然支持任意名字 */
    private val builtinTags = Prefs.BUILTIN_TAGS

    /* -------- 统一重复时间段：开始 + 结束 + 历法 + 方式 + 每天时刻 -------- */

    /** 开始日期是否按农历选（农历任务 = 按年重复，库里只存月日） */
    private var startLunar = false

    /** 结束日期是否按农历选（换算成公历存 endDate；仅影响选择交互） */
    private var endLunar = false

    private var startDate = AppDatabase.todayKey()

    /** 农历开始日的月/日（startLunar 时生效，落 anchorDate "MM-dd"） */
    private var lunarMonth = 1
    private var lunarDay = 1

    /** 结束日期（公历存值），空串 = 长期不结束（永久） */
    private var endDate = ""

    /** 期间内方式：[RepeatRule.PAT_WEEKDAY] / [RepeatRule.PAT_EVERY_N] / [RepeatRule.PAT_MONTHLY] */
    private var pattern = RepeatRule.PAT_WEEKDAY

    /** 「每 N 天」的 N（0 = 不按天） */
    private var intervalDays = 0

    /** 每月/每年任务的「几号」多选（bit d-1 = d 号）；0 = 沿用开始日期的日 */
    private var monthDaysMask = 0

    /** 「每年」的月份多选（bit0-11 = 1-12 月）；0 = 沿用开始日期的月 */
    private var yearMonthsMask = 0

    /** 农历「每 N 年」（落 intervalMonths；公历方式忽略） */
    private var intervalYears = 1

    /** 建档时间（农历「每 N 年」的锚年按它推算；新任务 = 现在） */
    private var createdAtMs = System.currentTimeMillis()

    /* -------- 每天时刻 -------- */

    private var timeMode = TIME_FIXED

    private val fixedTimes = mutableListOf<Int>()
    private var ivStart = 8 * 60
    private var ivStep = 60
    private var ivEnd = 22 * 60

    private var timeSpecActive = true

    private var timeOffsets = ""

    /** 择一提醒（v3.34.96）：多时刻任务确认任一个即算今天完成 */
    private var eitherMode = false

    private var wantClock = false
    private var wantCalendar = false

    private val calendarPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val granted = result[Manifest.permission.READ_CALENDAR] == true &&
                    result[Manifest.permission.WRITE_CALENDAR] == true
            if (granted) {
                toast(getString(R.string.link_ok))
            } else {
                wantCalendar = false
                findViewById<MaterialSwitch>(R.id.switchCalendar).isChecked = false
                toast(getString(R.string.link_calendar_perm_needed))
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_task_edit)

        taskId = intent.getLongExtra(EXTRA_TASK_ID, -1L)
        findViewById<TextView>(R.id.tvTitleBar).text =
            if (taskId > 0) getString(R.string.edit_title) else getString(R.string.edit_new)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        setupDeadlinePicker()
        setupRestDayDeadlinePicker()
        setupOffsetChips()
        setupRepeatSection()
        setupWeekdayChips()
        setupTimeSection()
        setupTagChips()
        setupPriorityChips()
        setupHolidaySection()
        setupLinkSection()

        findViewById<Button>(R.id.btnSave).setOnClickListener { save() }
        findViewById<Button>(R.id.btnDelete).setOnClickListener { confirmDelete() }

        if (taskId > 0) {
            findViewById<View>(R.id.btnDelete).visibility = View.VISIBLE
            load()
        } else {
            findViewById<View>(R.id.btnDelete).visibility = View.GONE
            renderAll()
        }
    }

    /* ---------------- 小米联动区块 ---------------- */

    private fun setupLinkSection() {
        val swClock = findViewById<MaterialSwitch>(R.id.switchClock)
        val swCalendar = findViewById<MaterialSwitch>(R.id.switchCalendar)

        // 设备没时钟应用、或任务是按日期重复（小米时钟表达不了）时，这个开关直接禁用
        updateClockSwitch()
        // 日历开关同理：过期的一次性 / 已结束的期间没有可写的到期日，跟着禁用
        updateCalendarSwitch()

        swClock.setOnCheckedChangeListener { _, checked ->
            wantClock = checked
        }
        swCalendar.setOnCheckedChangeListener { _, checked ->
            wantCalendar = checked
            if (checked && !MiLinkBridge.hasCalendarPermission(this)) {
                calendarPermLauncher.launch(
                    arrayOf(
                        Manifest.permission.READ_CALENDAR,
                        Manifest.permission.WRITE_CALENDAR
                    )
                )
            }
        }

        findViewById<Button>(R.id.btnOpenClock).setOnClickListener {
            if (!MiLinkBridge.openClockAlarms(this)) toast(getString(R.string.link_failed))
        }
        findViewById<Button>(R.id.btnOpenCalendar).setOnClickListener {
            if (!MiLinkBridge.openCalendar(this)) toast(getString(R.string.link_failed))
        }
    }

    private fun updateClockSwitch() {
        val sw = findViewById<MaterialSwitch>(R.id.switchClock)
        val desc = findViewById<TextView>(R.id.tvClockDesc)
        val hasClock = MiLinkBridge.canLinkClock(this)
        val mode = effectiveRepeatMode()
        val noClockSync = mode == RepeatRule.TYPE_ONCE ||
                mode == RepeatRule.TYPE_DATE ||
                mode == RepeatRule.TYPE_LUNAR
        sw.isEnabled = hasClock && !noClockSync
        if (!sw.isEnabled) {
            sw.isChecked = false
            wantClock = false
        }
        desc.text = getString(R.string.link_clock_desc) + when {
            !hasClock -> getString(R.string.link_no_clock_app_suffix)
            mode == RepeatRule.TYPE_ONCE -> getString(R.string.link_clock_once_suffix)
            noClockSync -> getString(R.string.link_clock_no_date_suffix)
            else -> ""
        }
    }

    private fun updateCalendarSwitch() {
        val sw = findViewById<MaterialSwitch>(R.id.switchCalendar)
        val desc = findViewById<TextView>(R.id.tvCalendarDesc)
        val mode = effectiveRepeatMode()
        val today = AppDatabase.todayKey()
        val expired = when (mode) {
            // 仅一次：日期落在 startDate（开始 = 结束同天），过了就不再响
            RepeatRule.TYPE_ONCE -> startDate.isNotBlank() && startDate < today
            // 其余类型：设了结束日期且已过 = 期间结束；没设结束（永久）总有一天会响
            else -> endDate.isNotBlank() && endDate < today
        }
        sw.isEnabled = !expired
        if (!sw.isEnabled) {
            sw.isChecked = false
            wantCalendar = false
        }
        desc.text = getString(R.string.link_calendar_desc) + when {
            !sw.isEnabled && mode == RepeatRule.TYPE_ONCE ->
                getString(R.string.link_calendar_past_suffix)
            !sw.isEnabled -> getString(R.string.link_calendar_ended_suffix)
            else -> ""
        }
    }

    /* ---------------- 加载与渲染 ---------------- */

    private fun load() {
        lifecycleScope.launch {
            val t = withContext(Dispatchers.IO) {
                AppDatabase.get(this@TaskEditActivity).taskDao().getById(taskId)
            }
            if (t == null) {
                finish()
                return@launch
            }
            findViewById<EditText>(R.id.etName).setText(t.name)
            findViewById<EditText>(R.id.etTarget).setText(
                if (t.targetCount > 0) t.targetCount.toString() else ""
            )
            findViewById<EditText>(R.id.etReps).setText(
                if (t.repsPerSet > 0) t.repsPerSet.toString() else ""
            )
            deadlineMin = t.deadlineMin
            restDayDeadlineMin = t.restDayDeadlineMin
            preOffset = t.preOffsetMin
            postOffset = t.postOffsetMin
            repeatMask = t.repeatMask
            createdAt = t.createdAt
            skipHolidays = t.skipHolidays
            tag = t.tag
            repsPerSet = t.repsPerSet
            priority = t.priority
            findViewById<EditText>(R.id.etItems).setText(t.items)
            endDate = t.endDate
            createdAtMs = t.createdAt
            renderTag()
            wantClock = t.linkedToClock
            wantCalendar = t.linkedToCalendar
            findViewById<MaterialSwitch>(R.id.switchClock).isChecked = wantClock
            findViewById<MaterialSwitch>(R.id.switchCalendar).isChecked = wantCalendar
            findViewById<MaterialSwitch>(R.id.switchSkipHolidays).isChecked = skipHolidays

            // ── 统一重复时间段反推：开始 / 结束 / 历法 / 方式 / 每天时刻 ──
            startLunar = t.repeatType == RepeatRule.TYPE_LUNAR
            endLunar = startLunar   // 农历任务的结束日期也用农历回显（换算往返稳定）
            if (startLunar) {
                // anchorDate 是农历 "MM-dd"；开始日不存年（按年重复）
                val (m, d) = lunarParts(t.anchorDate)
                lunarMonth = m; lunarDay = d
                intervalYears = t.intervalMonths.coerceAtLeast(1)
                pattern = RepeatRule.PAT_WEEKDAY   // 农历没有期间内方式，占位
                startDate = AppDatabase.todayKey()
            } else {
                pattern = when {
                    t.repeatType == RepeatRule.TYPE_DATE && t.intervalDays > 0 ->
                        RepeatRule.PAT_EVERY_N
                    t.repeatType == RepeatRule.TYPE_DATE && t.intervalMonths == 12 ->
                        RepeatRule.PAT_YEARLY
                    t.repeatType == RepeatRule.TYPE_DATE -> RepeatRule.PAT_MONTHLY
                    else -> RepeatRule.PAT_WEEKDAY
                }
                monthDaysMask =
                    if (t.repeatType == RepeatRule.TYPE_DATE) RepeatRule.dayMaskOf(t.repeatMask) else 0
                yearMonthsMask = t.monthsMask
                intervalDays = t.intervalDays
                // 老任务（anchorDate 为空）回显建档日而不是今天：它的起点本来就是建档日
                // （v3.34.7 起引擎也这么兜底），显示成"今天"会让人以为它昨晚才存在
                startDate = t.anchorDate.ifBlank {
                    AppDatabase.keyOf(java.util.Date(t.createdAt))
                }
                // 一次性任务：结束补齐为当天（老数据 endDate 为空）。不补的话重存会被
                // 误判成「永久星期任务」，类型就被悄悄改掉了
                if (t.repeatType == RepeatRule.TYPE_ONCE && t.anchorDate.isNotBlank()) {
                    endDate = t.anchorDate
                }
            }
            // 时刻表：老任务（空）先用旧截止初始化固定时刻，用户不动它就维持旧单时刻
            if (t.timeSpec.isNotBlank()) {
                timeSpecActive = true
                if (TimeSpec.isInterval(t.timeSpec)) {
                    timeMode = TIME_INTERVAL
                    TimeSpec.parseInterval(t.timeSpec)?.let { (s, st, e) ->
                        ivStart = s; ivStep = st; ivEnd = e
                    }
                } else {
                    timeMode = TIME_FIXED
                    val parsed = TimeSpec.timesOfDay(t.timeSpec)
                    if (parsed.isNotEmpty()) {
                        fixedTimes.clear(); fixedTimes.addAll(parsed)
                    }
                }
            } else {
                timeSpecActive = false
                timeMode = TIME_FIXED
                fixedTimes.clear(); fixedTimes.add(deadlineMin)
            }
            timeOffsets = t.timeOffsets
            eitherMode = t.eitherMode
            renderAll()
        }
    }

    private fun renderAll() {
        renderDeadline()
        renderOffsets()
        renderPeriod()
        renderTimeSection()
        renderPriority()
        renderPreview()
        renderHolidayHint()
    }

    /* ---------------- 优先级 ---------------- */

    private fun setupPriorityChips() {
        mapOf(
            R.id.chipPriorityHigh to Task.PRIORITY_HIGH,
            R.id.chipPriorityMid to Task.PRIORITY_MID,
            R.id.chipPriorityLow to Task.PRIORITY_LOW
        ).forEach { (id, v) ->
            findViewById<MaterialButton>(id).setOnClickListener {
                priority = v
                renderPriority()
            }
        }
    }

    private fun renderPriority() {
        Ui.chip(findViewById(R.id.chipPriorityHigh), priority == Task.PRIORITY_HIGH)
        Ui.chip(findViewById(R.id.chipPriorityMid), priority == Task.PRIORITY_MID)
        Ui.chip(findViewById(R.id.chipPriorityLow), priority == Task.PRIORITY_LOW)
    }

    private fun renderDeadline() {
        findViewById<Button>(R.id.btnDeadline).text = Task.formatMin(deadlineMin)
        findViewById<Button>(R.id.btnRestDayDeadline).text =
            if (restDayDeadlineMin >= 0) Task.formatMin(restDayDeadlineMin)
            else getString(R.string.rest_day_same)
        // 目标时间变了，开始 / 截止钟点要跟着重算（锚点 = 目标时刻）
        renderOffsets()
    }

    /** 法定休息日时间：点一次设置，长按恢复「与上方相同」 */
    private fun setupRestDayDeadlinePicker() {
        val btn = findViewById<Button>(R.id.btnRestDayDeadline)
        btn.setOnClickListener {
            val base = if (restDayDeadlineMin >= 0) restDayDeadlineMin else deadlineMin
            TimeSheet(
                this,
                getString(R.string.f_rest_day_deadline),
                base
            ) { min ->
                restDayDeadlineMin = min
                renderDeadline(); renderPreview()
            }.show()
        }
        btn.setOnLongClickListener {
            restDayDeadlineMin = -1
            renderDeadline(); renderPreview()
            Toast.makeText(this, R.string.rest_day_clear, Toast.LENGTH_SHORT).show()
            true
        }
    }

    private fun renderOffsets() {
        findViewById<MaterialButton>(R.id.btnPreOffset).text = when {
            preOffset < 0 -> getString(R.string.chip_off)
            else -> Task.formatMin(anchorMin - preOffset)
        }
        findViewById<MaterialButton>(R.id.btnPostOffset).text = when {
            postOffset < 0 -> getString(R.string.chip_off)
            else -> Task.formatMin(anchorMin + postOffset)
        }
        renderTimeOffsetRows()
    }

    /** 当前时刻表（编辑态）的时刻列表升序 */
    private fun editingTimes(): List<Int> = when {
        !timeSpecActive -> listOf(deadlineMin)
        timeMode == TIME_FIXED -> fixedTimes.sorted()
        else -> TimeSpec.timesOfDay(TimeSpec.encodeInterval(ivStart, ivStep, ivEnd))
    }

    private fun renderTimeOffsetRows() {
        val box = findViewById<LinearLayout>(R.id.llTimeOffsetRows)
        val wrap = findViewById<View>(R.id.rowTimeOffsets)
        val times = editingTimes()
        // v3.34.89：只在「固定时刻」模式且时刻数量可控时逐条列出；
        // 间隔循环会滚出一长串时刻（08:00–22:00 每 60 分 = 15 条），没必要全摊开
        val show = timeSpecActive && timeMode == TIME_FIXED &&
            times.size > 1 && times.size <= TimeOffsets.MAX_ENTRIES
        wrap.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) return

        box.removeAllViews()
        val d = resources.displayMetrics.density
        times.forEach { timeMin ->
            val pre = TimeOffsets.preOf(timeOffsets, timeMin, preOffset)
            val post = TimeOffsets.postOf(timeOffsets, timeMin, postOffset)
            val custom = TimeOffsets.has(timeOffsets, timeMin)

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                val ty = (3 * d).toInt()
                setPadding(0, ty, 0, ty)
            }
            val label = TextView(this).apply {
                text = Task.formatMin(timeMin)
                textSize = 14f
                setTextColor(ContextCompat.getColor(this@TaskEditActivity, R.color.app_text))
                if (custom) {
                    paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
                }
                layoutParams = LinearLayout.LayoutParams((58 * d).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            row.addView(label)

            row.addView(
                offsetChip(
                    when {
                        pre < 0 -> getString(R.string.chip_off)
                        else -> Task.formatMin(timeMin - pre)
                    }
                ) {
                    openClockSheet(pre, timeMin, isPre = true)
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            row.addView(
                offsetChip(
                    when {
                        post < 0 -> getString(R.string.chip_off)
                        else -> Task.formatMin(timeMin + post)
                    }
                ) {
                    openClockSheet(post, timeMin, isPre = false)
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            box.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        // 回到「全部统一」：清掉整张表，落库就是空串
        findViewById<MaterialButton>(R.id.btnApplyAllOffsets).setOnClickListener {
            timeOffsets = ""
            renderOffsets(); renderPreview()
            toast("已让全部时刻用同一组开始 / 复查时间")
        }
    }

    /** 逐时刻行里的「始 / 止」小按钮（描边、圆角、均分宽度） */
    private fun offsetChip(label: String, onClick: () -> Unit): MaterialButton =
        MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = label
            isAllCaps = false
            insetTop = 0
            insetBottom = 0
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (44 * resources.displayMetrics.density).toInt()
            ).apply { marginEnd = (6 * resources.displayMetrics.density).toInt() }
            setOnClickListener { onClick() }
        }

    private fun openClockSheet(currentOffset: Int, timeMin: Int, isPre: Boolean) {
        val target = timeMin
        val off = currentOffset < 0
        val init = if (off) target
        else if (isPre) ClockOffset.startClock(target, currentOffset)
        else ClockOffset.endClock(target, currentOffset)
        val range = if (isPre) ClockOffset.startRange(target) else ClockOffset.endRange(target)
        val title = getString(
            if (isPre) R.string.pre_sheet_title else R.string.post_sheet_title
        ) + " · " + Task.formatMin(timeMin)
        ClockSheet(
            this, title, init, range.first, range.last, off
        ) { picked ->
            // 一次只改一侧，另一侧原样带回（with() 需要完整的 pre/post 对）
            val curPre = TimeOffsets.preOf(timeOffsets, timeMin, preOffset)
            val curPost = TimeOffsets.postOf(timeOffsets, timeMin, postOffset)
            val next = if (isPre) {
                if (picked < 0) Task.OFFSET_OFF
                else ClockOffset.startOffset(target, picked)
            } else {
                if (picked < 0) Task.OFFSET_OFF
                else ClockOffset.endOffset(target, picked)
            }
            timeOffsets = TimeOffsets.with(
                timeOffsets, timeMin,
                if (isPre) next else curPre,
                if (isPre) curPost else next,
                preOffset, postOffset
            )
            renderOffsets(); renderPreview()
        }.show()
    }

    private val anchorMin: Int
        get() {
            if (!timeSpecActive) return deadlineMin
            val times = if (timeMode == TIME_FIXED) fixedTimes
            else TimeSpec.timesOfDay(TimeSpec.encodeInterval(ivStart, ivStep, ivEnd))
            return times.minOrNull() ?: deadlineMin
        }

    /** 分钟数 → 简短文案：45 → 「45 分」，60 → 「1 小时」，90 → 「1 小时 30 分」 */
    private fun fmtOffset(min: Int): String = when {
        min < 60 -> "${min} 分"
        min % 60 == 0 -> "${min / 60} 小时"
        else -> "${min / 60} 小时 ${min % 60} 分"
    }

    /** 日常 / 法定工作日 / 法定休息日 —— 这三个由法定日历判定，与「星期几」无关 */
    private fun isCalendarPreset(): Boolean =
        repeatMask == RepeatRule.DAILY ||
                repeatMask == RepeatRule.WEEKDAYS ||
                repeatMask == RepeatRule.WEEKEND

    private fun effectiveRepeatMode(): Int = when {
        startLunar -> RepeatRule.TYPE_LUNAR
        endDate.isNotBlank() && endDate == startDate && pattern == RepeatRule.PAT_WEEKDAY ->
            RepeatRule.TYPE_ONCE
        pattern == RepeatRule.PAT_WEEKDAY -> RepeatRule.TYPE_WEEKDAY
        else -> RepeatRule.TYPE_DATE
    }

    /* ---------------- 重复时间段：开始 + 结束 + 方式 ---------------- */

    private fun setupRepeatSection() {
        // 期间内方式（农历任务没有方式：按年循环，间隔用「每 N 年」）
        findViewById<MaterialButton>(R.id.btnPatWeekday).setOnClickListener {
            pattern = RepeatRule.PAT_WEEKDAY
            renderPeriod(); renderPreview()
        }
        findViewById<MaterialButton>(R.id.btnPatEveryN).setOnClickListener {
            pattern = RepeatRule.PAT_EVERY_N
            if (startDate.isBlank()) startDate = AppDatabase.todayKey()
            renderPeriod(); renderPreview()
        }
        findViewById<MaterialButton>(R.id.btnPatMonthly).setOnClickListener {
            pattern = RepeatRule.PAT_MONTHLY
            if (startDate.isBlank()) startDate = AppDatabase.todayKey()
            renderPeriod(); renderPreview()
        }
        findViewById<MaterialButton>(R.id.btnPatYearly).setOnClickListener {
            pattern = RepeatRule.PAT_YEARLY
            if (startDate.isBlank()) startDate = AppDatabase.todayKey()
            renderPeriod(); renderPreview()
        }

        // 开始日期：公历月历或农历月日联动滚轮各自可选；与结束日期同天即「仅一次」
        findViewById<MaterialButton>(R.id.btnStartDate).setOnClickListener {
            val initial = if (startLunar) DayPick.Lunar(0, lunarMonth, lunarDay)
            else DayPick.Solar(startDate.ifBlank { AppDatabase.todayKey() })
            DateSheet(this, getString(R.string.f_start_date), initial) { pick ->
                when (pick) {
                    is DayPick.Solar -> {
                        startLunar = false
                        startDate = pick.key
                    }
                    is DayPick.Lunar -> {
                        startLunar = true
                        lunarMonth = pick.month
                        lunarDay = pick.day
                    }
                    is DayPick.Never -> Unit   // 开始日期没有「长期」
                }
                renderPeriod(); renderPreview()
            }.show()
        }

        // 结束日期：可设长期（永久 = 不设结束时间）；公历 / 农历都可选
        // （农历带年轮的一体化滚轮，换算成公历落库，存值口径不变）
        findViewById<MaterialButton>(R.id.btnEndDate).setOnClickListener {
            val initial = when {
                endDate.isNotBlank() && endLunar ->
                    runCatching { LunarCalendar.solarToLunar(java.time.LocalDate.parse(endDate)) }
                        .getOrNull()?.let { (y, m, d) -> DayPick.Lunar(y, m, d) }
                        ?: DayPick.Solar(endDate)
                endDate.isNotBlank() -> DayPick.Solar(endDate)
                endLunar -> {
                    val (m, d) = todayLunar()
                    DayPick.Lunar(java.time.LocalDate.now().year, m, d)
                }
                else -> DayPick.Solar(startDate.ifBlank { AppDatabase.todayKey() })
            }
            DateSheet(
                this, getString(R.string.f_end), initial,
                withYear = true, allowNever = true
            ) { pick ->
                when (pick) {
                    is DayPick.Never -> endDate = ""
                    is DayPick.Solar -> {
                        endLunar = false
                        endDate = pick.key
                    }
                    is DayPick.Lunar -> {
                        endLunar = true
                        endDate = LunarCalendar.lunarToSolar(pick.year, pick.month, pick.day)
                            ?.toString() ?: endDate
                    }
                }
                renderPeriod(); renderPreview()
            }.show()
        }
        // 清除结束日期 = 回到「长期」
        findViewById<Button>(R.id.btnEndDateClear).setOnClickListener {
            endDate = ""
            renderPeriod(); renderPreview()
        }
        // 间隔数量：每 N 天（1–31）或农历每 N 年（1–30）
        // （每月 N 号的 N 就是开始日期的「日」，不单独设）
        findViewById<MaterialButton>(R.id.btnIntervalAmount).setOnClickListener {
            if (startLunar) {
                // 农历的间隔只有「每 N 年」有意义（生日每年 / 本命年每 12 年）
                WheelSheet(
                    this,
                    getString(R.string.interval_sheet_title),
                    (1..30).map { getString(R.string.interval_years_fmt, it) },
                    (intervalYears - 1).coerceIn(0, 29)
                ) { picked ->
                    intervalYears = picked + 1
                    renderPeriod(); renderPreview()
                }.show()
            } else {
                WheelSheet(
                    this,
                    getString(R.string.interval_sheet_title),
                    (1..31).map { getString(R.string.interval_days_fmt, it) },
                    (intervalDays.coerceAtLeast(1) - 1).coerceIn(0, 30)
                ) { picked ->
                    intervalDays = picked + 1
                    renderPeriod(); renderPreview()
                }.show()
            }
        }
    }

    private fun setupWeekdayChips() {
        val ids = intArrayOf(
            R.id.chipDay0, R.id.chipDay1, R.id.chipDay2, R.id.chipDay3,
            R.id.chipDay4, R.id.chipDay5, R.id.chipDay6
        )
        ids.forEachIndexed { i, id ->
            findViewById<MaterialButton>(id).setOnClickListener {
                repeatMask = repeatMask xor (1 shl i)
                if (repeatMask == 0) repeatMask = 1 shl i   // 不允许全空
                renderPeriod()
            }
        }

        findViewById<Button>(R.id.btnRepeatDaily).setOnClickListener {
            repeatMask = RepeatRule.DAILY; renderPeriod()
        }
        findViewById<Button>(R.id.btnRepeatWeekdays).setOnClickListener {
            repeatMask = RepeatRule.WEEKDAYS; renderPeriod()
        }
        findViewById<Button>(R.id.btnRepeatWeekend).setOnClickListener {
            repeatMask = RepeatRule.WEEKEND; renderPeriod()
        }
        findViewById<Button>(R.id.btnRepeatCustom).setOnClickListener {
            // 从预设切过来时先退成「仅今天」，再点星期芯片自己挑；本来就是自定义的保持不动
            if (isCalendarPreset()) repeatMask = RepeatRule.todayMask()
            renderPeriod()
        }
    }

    /** 数字芯片网格（月：1-31 排 7 列；年：1-12 排 6 列），点选切换 */
    private fun renderNumberChips(
        grid: android.widget.GridLayout,
        count: Int,
        isOn: (Int) -> Boolean,
        onToggle: (Int) -> Unit
    ) {
        grid.removeAllViews()
        val d = resources.displayMetrics.density
        for (n in 1..count) {
            val chip = MaterialButton(
                this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                text = n.toString()
                isAllCaps = false
                textSize = 14f
                insetTop = 0
                insetBottom = 0
                setPadding(0, 0, 0, 0)
                layoutParams = android.widget.GridLayout.LayoutParams(
                    android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED),
                    android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)
                ).apply {
                    width = 0
                    height = (40 * d).toInt()
                    setMargins((3 * d).toInt(), (4 * d).toInt(), (3 * d).toInt(), 0)
                }
                setOnClickListener { onToggle(n) }
            }
            Ui.chip(chip, isOn(n), radiusDp = 8)
            grid.addView(chip)
        }
    }

    private fun renderPeriod() {
        val lunar = startLunar
        val mode = effectiveRepeatMode()
        val useWeekday = !lunar && pattern == RepeatRule.PAT_WEEKDAY
        val useDateRule = !lunar && pattern != RepeatRule.PAT_WEEKDAY

        // 星期芯片选中态（自定义星期 / 非预设档时可见）
        val dayIds = intArrayOf(
            R.id.chipDay0, R.id.chipDay1, R.id.chipDay2, R.id.chipDay3,
            R.id.chipDay4, R.id.chipDay5, R.id.chipDay6
        )
        dayIds.forEachIndexed { i, id ->
            // 星期芯片圆角比其他芯片小一号（8dp，与页签/按钮同语言），按用户要求
            Ui.chip(findViewById(id), (repeatMask and (1 shl i)) != 0, radiusDp = 8)
        }
        Ui.chip(findViewById(R.id.btnRepeatDaily), repeatMask == RepeatRule.DAILY)
        Ui.chip(findViewById(R.id.btnRepeatWeekdays), repeatMask == RepeatRule.WEEKDAYS)
        Ui.chip(findViewById(R.id.btnRepeatWeekend), repeatMask == RepeatRule.WEEKEND)
        Ui.chip(findViewById(R.id.btnRepeatCustom), !isCalendarPreset())

        // 方式行：农历任务没有方式（按年循环）
        findViewById<View>(R.id.rowPattern).visibility =
            if (lunar) View.GONE else View.VISIBLE
        Ui.chip(findViewById(R.id.btnPatWeekday), pattern == RepeatRule.PAT_WEEKDAY)
        Ui.chip(findViewById(R.id.btnPatEveryN), pattern == RepeatRule.PAT_EVERY_N)
        Ui.chip(findViewById(R.id.btnPatMonthly), pattern == RepeatRule.PAT_MONTHLY)
        Ui.chip(findViewById(R.id.btnPatYearly), pattern == RepeatRule.PAT_YEARLY)

        // 星期芯片只对「自定义」预设之外有意义：日常 / 工作日 / 休息日由法定日历判定
        findViewById<View>(R.id.rowWeekdayChips).visibility =
            if (useWeekday && !isCalendarPreset()) View.VISIBLE else View.GONE
        findViewById<View>(R.id.rowWeekdayQuick).visibility =
            if (useWeekday) View.VISIBLE else View.GONE

        // 月：1-31 多选；年：1-12 月多选（整月提醒）
        val monthly = useDateRule && pattern == RepeatRule.PAT_MONTHLY
        val yearly = useDateRule && pattern == RepeatRule.PAT_YEARLY
        findViewById<View>(R.id.gridMonthDayChips).visibility =
            if (monthly) View.VISIBLE else View.GONE
        findViewById<View>(R.id.gridMonthChips).visibility =
            if (yearly) View.VISIBLE else View.GONE
        if (monthly) {
            renderNumberChips(
                findViewById(R.id.gridMonthDayChips), 31,
                isOn = { (monthDaysMask and (1 shl (it - 1))) != 0 },
                onToggle = { day ->
                    monthDaysMask = monthDaysMask xor (1 shl (day - 1))
                    renderPeriod(); renderPreview()
                }
            )
        } else if (yearly) {
            renderNumberChips(
                findViewById(R.id.gridMonthChips), 12,
                isOn = { (yearMonthsMask and (1 shl (it - 1))) != 0 },
                onToggle = { m ->
                    yearMonthsMask = yearMonthsMask xor (1 shl (m - 1))
                    renderPeriod(); renderPreview()
                }
            )
        }

        // 开始日期按钮：农历显示月日，公历显示日期（永久也从这一天起重复）
        findViewById<MaterialButton>(R.id.btnStartDate).text =
            if (lunar) "农历${LunarCalendar.monthName(lunarMonth)}${LunarCalendar.dayName(lunarDay)}"
            else startDate.ifBlank { AppDatabase.todayKey() }

        // 间隔：农历 = 每 N 年；每 N 天 = 每 N 天
        // （每月 N 号的 N 就是开始日期的「日」，不单独设）
        val showInterval = lunar || (useDateRule && pattern == RepeatRule.PAT_EVERY_N)
        findViewById<View>(R.id.tvIntervalLabel).visibility =
            if (showInterval) View.VISIBLE else View.GONE
        findViewById<View>(R.id.rowIntervalAmount).visibility =
            if (showInterval) View.VISIBLE else View.GONE
        findViewById<MaterialButton>(R.id.btnIntervalAmount).text =
            if (lunar) getString(R.string.interval_years_fmt, intervalYears)
            else getString(R.string.interval_days_fmt, intervalDays.coerceAtLeast(1))

        // 结束日期按钮：长期 / 公历日 / 农历回显（存值仍是公历）
        findViewById<MaterialButton>(R.id.btnEndDate).text = endDateLabel()

        // 摘要：一句话说清「什么时候、按什么规则、响到什么时候」
        findViewById<TextView>(R.id.tvRepeatSummary).text = when {
            lunar -> buildString {
                append(if (intervalYears > 1) "每 ${intervalYears} 年" else "每年")
                append("农历").append(LunarCalendar.monthName(lunarMonth))
                    .append(LunarCalendar.dayName(lunarDay))
                if (endDate.isNotBlank()) append(" · 至 ").append(endDateLabel())
            }
            mode == RepeatRule.TYPE_ONCE -> RepeatRule.describeOnce(startDate)
            useDateRule -> RepeatRule.describeDate(
                startDate.ifBlank { AppDatabase.todayKey() },
                if (pattern == RepeatRule.PAT_YEARLY) 12 else 1,
                endDate,
                if (pattern == RepeatRule.PAT_EVERY_N) intervalDays.coerceAtLeast(1) else 0,
                when (pattern) {
                    RepeatRule.PAT_MONTHLY -> RepeatRule.dayMaskOf(monthDaysMask)
                    RepeatRule.PAT_YEARLY -> RepeatRule.dayMaskOf(RepeatRule.FULL_MONTH_MASK)
                    else -> 0
                },
                if (pattern == RepeatRule.PAT_YEARLY) yearMonthsMask else 0
            )
            else -> RepeatRule.describe(repeatMask) +
                    if (endDate.isNotBlank()) " · 至 $endDate" else ""
        }

        // 「工作日」提醒的任务永远不会落在休息日；一次性 / 农历任务只在那一天 —— 都不需要休息日时间
        findViewById<View>(R.id.boxRestDay).visibility =
            if (!timeSpecActive && repeatMask != RepeatRule.WEEKDAYS &&
                mode != RepeatRule.TYPE_ONCE && mode != RepeatRule.TYPE_LUNAR
            ) View.VISIBLE else View.GONE
        // 旧「截止时间」行只服务未启用时刻表的老任务
        findViewById<View>(R.id.boxDeadline).visibility =
            if (timeSpecActive) View.GONE else View.VISIBLE

        // 期间/方式会影响时钟能不能同步（按日期 / 一次性 → 小米时钟不支持），跟着刷新
        updateClockSwitch()
        // 日历开关同理：过期的一次性 / 已结束的期间没有可写的到期日，跟着禁用
        updateCalendarSwitch()
    }

    private fun endDateLabel(): String = when {
        endDate.isBlank() -> getString(R.string.date_never)
        endLunar -> runCatching { java.time.LocalDate.parse(endDate) }.getOrNull()
            ?.let { LunarCalendar.solarToLunar(it) }
            ?.let { (y, m, d) -> "农历${y}年${LunarCalendar.monthName(m)}${LunarCalendar.dayName(d)}" }
            ?: endDate
        else -> endDate
    }

    /** 今天的农历 (月, 日)；换算失败兜底正月初一 */
    private fun todayLunar(): Pair<Int, Int> {
        val t = LunarCalendar.solarToLunar(java.time.LocalDate.now()) ?: return 1 to 1
        return t.second to t.third
    }

    /* ---------------- 每天时刻 ---------------- */

    private fun setupTimeSection() {
        findViewById<MaterialButton>(R.id.btnTimeFixed).setOnClickListener {
            timeMode = TIME_FIXED
            timeSpecActive = true
            renderTimeSection(); renderPreview()
        }
        findViewById<MaterialButton>(R.id.btnTimeInterval).setOnClickListener {
            timeMode = TIME_INTERVAL
            timeSpecActive = true
            renderTimeSection(); renderPreview()
            // v3.34.89：切到间隔循环就直接弹参数窗，不用再找按钮
            openIntervalConfig()
        }
        findViewById<MaterialButton>(R.id.btnIvConfig).setOnClickListener {
            openIntervalConfig()
        }
    }

    private fun openIntervalConfig() {
        IntervalConfigSheet(this, ivStart, ivStep, ivEnd) { s, st, e ->
            ivStart = s
            ivStep = st
            ivEnd = e
            timeSpecActive = true
            renderTimeSection(); renderPreview()
        }.show()
    }

    private fun renderTimeSection() {
        Ui.chip(findViewById(R.id.btnTimeFixed), timeMode == TIME_FIXED)
        Ui.chip(findViewById(R.id.btnTimeInterval), timeMode == TIME_INTERVAL)
        findViewById<View>(R.id.rowFixedTimes).visibility =
            if (timeMode == TIME_FIXED) View.VISIBLE else View.GONE
        findViewById<View>(R.id.rowInterval).visibility =
            if (timeMode == TIME_INTERVAL) View.VISIBLE else View.GONE

        // 择一提醒开关（v3.34.96）：只有「固定时刻 ≥2 个」才有意义
        val swEither =
            findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.swEither)
        val multiFixed = timeMode == TIME_FIXED && fixedTimes.distinct().size > 1
        swEither.visibility = if (multiFixed) View.VISIBLE else View.GONE
        swEither.isChecked = eitherMode
        swEither.setOnCheckedChangeListener { _, checked -> eitherMode = checked }
        // 参数摘要（v3.34.89）：08:00 · 每 60 分 · 至 22:00 —— 点它打开参数弹窗
        findViewById<MaterialButton>(R.id.btnIvConfig).text =
            "${Task.formatMin(ivStart)} · 每 ${IntervalSheet.label(ivStep)} · " +
                getString(R.string.iv_end_fmt, Task.formatMin(ivEnd))

        // 时刻芯片：已有时刻（点一下删除）+「＋ 添加」；允许全部删空，保存时统一校验
        val row = findViewById<LinearLayout>(R.id.llTimeChips)
        row.removeAllViews()
        if (timeMode != TIME_FIXED) {
            // 时刻表变了，开始 / 截止钟点要跟着重算（锚点 = 最早时刻）
            renderOffsets()
            return
        }
        fixedTimes.sorted().forEach { min ->
            row.addView(timeChip(Task.formatMin(min), removable = true) {
                fixedTimes.remove(min)
                renderTimeSection(); renderPreview()
            })
        }
        // 添加按钮：图标，跟在时刻芯片右侧
        val d = resources.displayMetrics.density
        row.addView(ImageButton(this).apply {
            setImageResource(R.drawable.ic_add)
            imageTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(this@TaskEditActivity, R.color.app_text_sub)
            )
            val tv = android.util.TypedValue()
            if (theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)) {
                background = ContextCompat.getDrawable(this@TaskEditActivity, tv.resourceId)
            }
            layoutParams = LinearLayout.LayoutParams((44 * d).toInt(), (40 * d).toInt())
            contentDescription = getString(R.string.time_add)
            setOnClickListener {
                TimeSheet(this@TaskEditActivity, "添加时刻", 20 * 60) { m ->
                    if (m !in fixedTimes) fixedTimes.add(m)
                    timeSpecActive = true
                    renderTimeSection(); renderPreview()
                }.show()
            }
        })
        // 时刻表变了，开始 / 截止钟点要跟着重算（锚点 = 最早时刻）
        renderOffsets()
    }

    /** 时刻芯片：已有时刻点一下删除；走 Material 描边样式（动态创建，用 defStyleAttr 定样式） */
    private fun timeChip(label: String, removable: Boolean, onClick: () -> Unit): MaterialButton =
        MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = label
            isAllCaps = false
            insetTop = 0
            insetBottom = 0
            setOnClickListener { onClick() }
        }

    /** 实时预览提醒时间点，让「时刻 / 开始 / 复查」的关系一眼看懂 */
    private fun renderPreview() {
        val sb = StringBuilder()
        if (effectiveRepeatMode() == RepeatRule.TYPE_DATE && startDate.isNotBlank()) {
            val next = nextDueKey()
            sb.append(if (next != null) "· 下次 $next\n" else "· 已全部结束\n")
        }
        if (effectiveRepeatMode() == RepeatRule.TYPE_LUNAR) {
            val next = nextLunarDueKey()
            sb.append(if (next != null) "· 下次 $next\n" else "· 未设置农历日期\n")
        }
        if (timeSpecActive) {
            val times = if (timeMode == TIME_FIXED) fixedTimes.sorted()
            else TimeSpec.timesOfDay(TimeSpec.encodeInterval(ivStart, ivStep, ivEnd))
            if (times.isEmpty()) {
                sb.append("· 未添加时刻（点「＋ 添加」）\n")
            } else if (times.size == 1) {
                val d = times.first()
                sb.append("· ").append(Task.formatMin(d)).append(' ')
                    .append(getString(R.string.preview_line_deadline)).append('\n')
                if (preOffset >= 0) {
                    sb.append("· ").append(Task.formatMin(d - preOffset)).append(' ')
                        .append(getString(R.string.preview_line_pre)).append('\n')
                }
                if (postOffset >= 0) {
                    sb.append("· ").append(Task.formatMin(d + postOffset)).append(' ')
                        .append(getString(R.string.preview_line_post)).append('\n')
                }
                if (preOffset < 0 && postOffset < 0) {
                    sb.append(getString(R.string.preview_none)).append('\n')
                }
            } else {
                val shown = times.take(5).joinToString("、") { Task.formatMin(it) }
                val more = if (times.size > 5) " 等 ${times.size} 个" else ""
                sb.append("· ").append(times.size).append(" 个时刻：").append(shown).append(more).append('\n')
                // v3.34.21：每个时刻列出**自己**的开始 / 复查（多对多，不再是统一偏移）
                times.take(8).forEach { t ->
                    val pre = TimeOffsets.preOf(timeOffsets, t, preOffset)
                    val post = TimeOffsets.postOf(timeOffsets, t, postOffset)
                    sb.append("  · ").append(Task.formatMin(t))
                    if (pre >= 0) sb.append("  始 ").append(Task.formatMin(t - pre))
                    if (post >= 0) sb.append("  止 ").append(Task.formatMin(t + post))
                    if (pre < 0 && post < 0) sb.append("  ").append(getString(R.string.preview_none))
                    sb.append('\n')
                }
                if (times.size > 8) sb.append("  · …\n")
            }
        } else {
            val dl = anchorMin
            if (preOffset >= 0) {
                sb.append("· ").append(Task.formatMin(dl - preOffset))
                    .append("  ").append(getString(R.string.preview_line_pre)).append('\n')
            }
            sb.append("· ").append(Task.formatMin(dl))
                .append("  ").append(getString(R.string.preview_line_deadline))
            if (postOffset >= 0) {
                sb.append('\n').append("· ").append(Task.formatMin(dl + postOffset))
                    .append("  ").append(getString(R.string.preview_line_post))
            }
            if (preOffset < 0 && postOffset < 0) {
                sb.append('\n').append(getString(R.string.preview_none))
            }
            // 工作日任务不会落在休息日，别把这条无关的提醒也列进预览
            if (restDayDeadlineMin >= 0 && repeatMask != RepeatRule.WEEKDAYS) {
                sb.append('\n').append("· ").append(getString(R.string.preview_rest_day_prefix))
                    .append(' ').append(Task.formatMin(restDayDeadlineMin))
                    .append("  ").append(getString(R.string.preview_line_deadline))
            }
        }
        findViewById<TextView>(R.id.tvPreview).text = sb.toString()
    }

    /* ---------------- 交互 ---------------- */

    private fun setupDeadlinePicker() {
        findViewById<Button>(R.id.btnDeadline).setOnClickListener {
            TimeSheet(
                this,
                getString(R.string.f_deadline),
                deadlineMin
            ) { min ->
                deadlineMin = min
                // 旧单时刻任务的截止改动同步进时刻区，用户一碰时刻区就走新模型
                fixedTimes.clear(); fixedTimes.add(min)
                renderDeadline(); renderPreview()
            }.show()
        }
    }

    private fun setupOffsetChips() {
        findViewById<MaterialButton>(R.id.btnPreOffset).setOnClickListener {
            val target = anchorMin
            val off = preOffset < 0
            val init = if (off) target else ClockOffset.startClock(target, preOffset)
            ClockSheet(
                this,
                getString(R.string.pre_sheet_title),
                init,
                ClockOffset.startRange(target).first,
                ClockOffset.startRange(target).last,
                off
            ) { picked ->
                preOffset = if (picked < 0) Task.OFFSET_OFF
                else ClockOffset.startOffset(target, picked)
                renderOffsets(); renderPreview()
            }.show()
        }
        findViewById<MaterialButton>(R.id.btnPostOffset).setOnClickListener {
            val target = anchorMin
            val off = postOffset < 0
            val init = if (off) target else ClockOffset.endClock(target, postOffset)
            ClockSheet(
                this,
                getString(R.string.post_sheet_title),
                init,
                ClockOffset.endRange(target).first,
                ClockOffset.endRange(target).last,
                off
            ) { picked ->
                postOffset = if (picked < 0) Task.OFFSET_OFF
                else ClockOffset.endOffset(target, picked)
                renderOffsets(); renderPreview()
            }.show()
        }
    }

    /* ---------------- 农历与到期日推算 ---------------- */

    /** 下一个到期日（含今天），已全部结束返回 null */
    private fun nextDueKey(): String? {
        val anchor = startDate.ifBlank { AppDatabase.todayKey() }
        if (anchor.isBlank()) return null
        val interval = if (pattern == RepeatRule.PAT_YEARLY) 12 else 1
        val mask = if (pattern == RepeatRule.PAT_MONTHLY || pattern == RepeatRule.PAT_YEARLY)
            monthDaysMask else 0
        val cal = java.util.Calendar.getInstance()
        repeat(400) {
            val key = "%04d-%02d-%02d".format(
                cal.get(java.util.Calendar.YEAR),
                cal.get(java.util.Calendar.MONTH) + 1,
                cal.get(java.util.Calendar.DAY_OF_MONTH)
            )
            if (RepeatRule.isDateDue(
                    anchor, interval, endDate, key, intervalDays, RepeatRule.dayMaskOf(mask),
                    if (pattern == RepeatRule.PAT_YEARLY) yearMonthsMask else 0
                )
            ) {
                return key
            }
            cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
        }
        return null
    }

    /** 农历 "MM-dd"（或空串）→ (月, 日)；没设过返回 (1, 1) 正月初一 */
    private fun lunarParts(anchor: String): Pair<Int, Int> {
        val p = anchor.split("-")
        val m = p.getOrNull(0)?.toIntOrNull()?.coerceIn(1, 12) ?: 1
        val d = p.getOrNull(1)?.toIntOrNull()?.coerceIn(1, 30) ?: 1
        return m to d
    }

    /** 农历任务的下一个到期日（含今天，含间隔年与结束日期），向后扫一年 */
    private fun nextLunarDueKey(): String? {
        val base = RepeatRule.lunarBaseYear(lunarMonth, lunarDay, createdAtMs)
        val lunarAnchor = "%02d-%02d".format(lunarMonth, lunarDay)
        val cal = java.util.Calendar.getInstance()
        repeat(400) {
            val key = "%04d-%02d-%02d".format(
                cal.get(java.util.Calendar.YEAR),
                cal.get(java.util.Calendar.MONTH) + 1,
                cal.get(java.util.Calendar.DAY_OF_MONTH)
            )
            if (RepeatRule.isLunarDue(lunarAnchor, 0, key, endDate, intervalYears, base)) {
                return key
            }
            cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
        }
        return null
    }

    /* ---------------- 标签 ---------------- */

    /** 标签下拉按钮：按钮只显示当前标签，点开 TagSheet 选；左划列表项露出 重命名 / 删除 */
    private fun setupTagChips() {
        findViewById<MaterialButton>(R.id.btnTagPick).setOnClickListener {
            val customs = Prefs.customTags(this)
            TagSheet(
                this, tag,
                options = tagOptions().map { it to (it in customs) },
                onPick = { name ->
                    // 再点一次已选中的标签 = 取消分组
                    tag = if (tag == name) "" else name
                    renderTag()
                },
                onAdd = { promptNewTag() },
                onRename = { name -> promptRenameTag(name) },
                onDelete = { name -> deleteTag(name) },
            ).show()
        }
        renderTag()
    }

    /** 下拉按钮上显示当前标签（空 = 未分组） */
    private fun renderTag() {
        findViewById<MaterialButton>(R.id.btnTagPick).text =
            tag.ifBlank { getString(R.string.group_untagged) }
    }

    private fun tagOptions(): List<String> =
        (builtinTags - Prefs.hiddenBuiltinTags(this)) + Prefs.customTags(this).sorted()

    /** 自定义标签的入口弹窗：输入即建即用 */
    private fun promptNewTag() {
        TextSheet(this, getString(R.string.tag_custom_title), "") { name ->
            if (name.isNotEmpty()) {
                Prefs.addCustomTag(this, name)
                tag = name
                renderTag()
            }
        }.show()
    }

    private fun promptRenameTag(old: String) {
        TextSheet(this, getString(R.string.tag_rename), old) { name ->
            if (name.isNotEmpty() && name != old) {
                if (old in builtinTags) {
                    // 内置标签改名 = 藏掉原名 + 以新名作为自定义标签入库，任务一起搬
                    Prefs.hideBuiltinTag(this, old)
                    Prefs.addCustomTag(this, name)
                } else {
                    Prefs.renameCustomTag(this, old, name)
                }
                if (tag == old) tag = name
                lifecycleScope.launch(Dispatchers.IO) {
                    AppDatabase.get(this@TaskEditActivity).taskDao().migrateTag(old, name)
                }
                renderTag()
            }
        }.show()
    }

    private fun deleteTag(name: String) {
        if (name in builtinTags) Prefs.hideBuiltinTag(this, name)
        else Prefs.removeCustomTag(this, name)
        if (tag == name) tag = ""
        lifecycleScope.launch(Dispatchers.IO) {
            AppDatabase.get(this@TaskEditActivity).taskDao().migrateTag(name, "")
        }
        renderTag()
        Toast.makeText(this, R.string.tag_deleted_msg, Toast.LENGTH_SHORT).show()
    }

    /* ---------------- 法定节假日 / 调休 ---------------- */

    private fun setupHolidaySection() {
        val sw = findViewById<MaterialSwitch>(R.id.switchSkipHolidays)
        sw.isChecked = skipHolidays
        sw.setOnCheckedChangeListener { _, checked ->
            skipHolidays = checked
            renderHolidayHint()
        }
    }

    private fun renderHolidayHint() {
        val sw = findViewById<MaterialSwitch>(R.id.switchSkipHolidays)
        if (effectiveRepeatMode() == RepeatRule.TYPE_DATE ||
            effectiveRepeatMode() == RepeatRule.TYPE_ONCE ||
            effectiveRepeatMode() == RepeatRule.TYPE_LUNAR
        ) {
            // 账期不受节假日影响；一次性 / 农历任务只在那一天，也谈不上跳不跳
            sw.isEnabled = false
            sw.isChecked = false
            skipHolidays = false
            findViewById<TextView>(R.id.tvHolidayHint).text =
                getString(R.string.holiday_hint_date)
            return
        }
        val hint: String
        when (repeatMask) {
            RepeatRule.WEEKDAYS -> {
                sw.isEnabled = false
                if (!sw.isChecked) sw.isChecked = true
                skipHolidays = true
                hint = getString(R.string.holiday_hint_workday)
            }
            RepeatRule.WEEKEND -> {
                sw.isEnabled = false
                if (!sw.isChecked) sw.isChecked = true
                skipHolidays = true
                hint = getString(R.string.holiday_hint_weekend)
            }
            else -> {
                sw.isEnabled = true
                hint = getString(R.string.holiday_hint_other)
            }
        }

        val today = AppDatabase.todayKey()
        val sb = StringBuilder(hint)
            .append('\n')
            .append(getString(R.string.diag_holiday_today_fmt, HolidayCalendar.describe(HolidayCalendar.typeOf(today))))
        if (!HolidayCalendar.isCovered(today)) {
            sb.append('\n').append(getString(R.string.diag_holiday_uncovered))
        }
        findViewById<TextView>(R.id.tvHolidayHint).text = sb.toString()
    }

    /* ---------------- 保存 ---------------- */

    private fun save() {
        val name = findViewById<EditText>(R.id.etName).text.toString().trim()
        if (TextUtils.isEmpty(name)) {
            Toast.makeText(this, "请填写任务名称", Toast.LENGTH_SHORT).show()
            return
        }
        val targetStr = findViewById<EditText>(R.id.etTarget).text.toString().trim()
        val target = targetStr.toIntOrNull() ?: 0
        val reps = findViewById<EditText>(R.id.etReps).text.toString().trim()
            .toIntOrNull()?.coerceAtLeast(0) ?: 0

        // 时刻表：固定列表或间隔循环，落到 timeSpec；截止同步为首个时刻
        val timeSpec = if (timeSpecActive) {
            val ts = if (timeMode == TIME_FIXED) {
                TimeSpec.encodeFixed(fixedTimes)
            } else {
                if (ivEnd < ivStart) {
                    toast("「响到」不能早于开始时刻")
                    return
                }
                TimeSpec.encodeInterval(ivStart, ivStep, ivEnd)
            }
            if (ts.isBlank()) {
                toast("请先点「＋ 添加」至少加一个响铃时刻")
                return
            }
            ts
        } else ""

        if (timeSpec.isNotBlank()) {
            deadlineMin = TimeSpec.timesOfDay(timeSpec).firstOrNull() ?: deadlineMin
            restDayDeadlineMin = -1   // 时刻表模式下休息日变体不生效
            // 顺手扔掉已删时刻留下的僵尸配置（按分钟数存，本来就命不中，但别让表无限膨胀）
            timeOffsets = TimeOffsets.retain(timeOffsets, TimeSpec.timesOfDay(timeSpec))
        }

        // ── 重复时间段 → 落库字段（纯函数 resolvePlan：永久 / 仅一次 / 自定义 / 农历）──
        if (!startLunar && endDate.isNotBlank() && startDate.isNotBlank() && endDate < startDate) {
            toast(getString(R.string.err_end_before_start))
            return
        }
        // 每年：没选月份就沿用开始日期的月（至少一个月）
        val savedMonthsMask = if (!startLunar && pattern == RepeatRule.PAT_YEARLY) {
            if (yearMonthsMask != 0) yearMonthsMask
            else RepeatRule.maskOfMonths(listOf(
                startDate.ifBlank { AppDatabase.todayKey() }.substring(5, 7).toIntOrNull() ?: 1
            ))
        } else 0
        val plan = RepeatRule.resolvePlan(
            startLunar = startLunar,
            startKey = startDate.ifBlank { AppDatabase.todayKey() },
            endKey = endDate,
            pattern = pattern,
            intervalDays = intervalDays,
            intervalYears = intervalYears,
            lunarMonth = lunarMonth,
            lunarDay = lunarDay,
            dayMask = when (pattern) {
                RepeatRule.PAT_MONTHLY -> monthDaysMask
                RepeatRule.PAT_YEARLY -> RepeatRule.FULL_MONTH_MASK
                else -> 0
            }
        )

        // 清单：一行一项；去掉空行与首尾空白，存回换行分隔的文本
        val items = findViewById<EditText>(R.id.etItems).text.toString()
            .split('\n').map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")

        lifecycleScope.launch {
            val dao = AppDatabase.get(this@TaskEditActivity).taskDao()
            val saved = withContext(Dispatchers.IO) {
                if (taskId > 0) {
                    val old = dao.getById(taskId) ?: return@withContext null
                    val updated = old.copy(
                        name = name,
                        targetCount = target,
                        deadlineMin = deadlineMin,
                        restDayDeadlineMin = restDayDeadlineMin,
                        preOffsetMin = preOffset,
                        postOffsetMin = postOffset,
                        repsPerSet = reps,
                        repeatMask = if (plan.repeatType == RepeatRule.TYPE_DATE)
                            plan.repeatMask else repeatMask,
                        repeatType = plan.repeatType,
                        anchorDate = plan.anchorDate,
                        intervalMonths = plan.intervalMonths,
                        intervalDays = plan.intervalDays,
                        monthsMask = savedMonthsMask,
                        endDate = plan.endDate,
                        // 提前提醒已从界面移除，编辑保存归零（想要前一天提醒直接把日期选成前一天）
                        lunarLeadDays = 0,
                        skipHolidays = skipHolidays,
                        tag = tag,
                        priority = priority,
                        items = items,
                        timeSpec = timeSpec,
                        timeOffsets = timeOffsets,
                        // 择一只在「≥2 个固定时刻」时成立，否则落库为关
                        eitherMode = eitherMode &&
                            timeSpec.isNotBlank() &&
                            TimeSpec.timesOfDay(timeSpec).size > 1,
                        linkedToClock = wantClock,
                        linkedToCalendar = wantCalendar
                    )
                    dao.update(updated)
                    updated
                } else {
                    val newId = dao.insert(
                        Task(
                            name = name,
                            targetCount = target,
                            repsPerSet = reps,
                            doneCount = 0,
                            deadlineMin = deadlineMin,
                            restDayDeadlineMin = restDayDeadlineMin,
                            preOffsetMin = preOffset,
                            postOffsetMin = postOffset,
                            repeatMask = if (plan.repeatType == RepeatRule.TYPE_DATE)
                            plan.repeatMask else repeatMask,
                            repeatType = plan.repeatType,
                            anchorDate = plan.anchorDate,
                            intervalMonths = plan.intervalMonths,
                            intervalDays = plan.intervalDays,
                        monthsMask = savedMonthsMask,
                            endDate = plan.endDate,
                            lunarLeadDays = 0,
                            skipHolidays = skipHolidays,
                            tag = tag,
                            priority = priority,
                            items = items,
                            enabled = true,
                            sortOrder = System.currentTimeMillis().toInt(),
                            createdAt = System.currentTimeMillis(),
                            linkedToClock = wantClock,
                            linkedToCalendar = wantCalendar,
                            timeSpec = timeSpec,
                            timeOffsets = timeOffsets,
                            eitherMode = eitherMode &&
                                timeSpec.isNotBlank() &&
                                TimeSpec.timesOfDay(timeSpec).size > 1
                        )
                    )
                    taskId = newId
                    dao.getById(newId)
                }
            } ?: return@launch

            // 只重排这条任务：编辑不改其他任务的排期（v3.34.115）
            AlarmScheduler.scheduleLater(this@TaskEditActivity, saved)

            // ── 小米联动：保存成功后再推，保证能拿到稳定的任务 ID ──
            val linkResults = withContext(Dispatchers.IO) {
                val clockOk = if (wantClock) MiLinkBridge.pushToClock(this@TaskEditActivity, saved) else false
                val calOk = if (wantCalendar && MiLinkBridge.hasCalendarPermission(this@TaskEditActivity)) {
                    MiLinkBridge.pushToCalendar(this@TaskEditActivity, saved)
                } else false
                clockOk to calOk
            }
            // 时钟 / 日历哪边写失败都明说，不静默
            val failed = buildList {
                if (wantClock && !linkResults.first) add(getString(R.string.link_clock))
                if (wantCalendar && !linkResults.second) add(getString(R.string.link_calendar))
            }
            if (failed.isNotEmpty()) {
                toast(failed.joinToString("、") + getString(R.string.link_failed))
            }

            // 首次创建且精确闹钟权限缺失时，直接引导去开
            if (!Permissions.canScheduleExactAlarms(this@TaskEditActivity)
                && !Prefs.hasShownExactHint(this@TaskEditActivity)
            ) {
                Prefs.markExactHintShown(this@TaskEditActivity)
                com.norbury.taktu.sheet.ConfirmSheet(
                    this@TaskEditActivity,
                    title = "还差一步：开启闹钟权限",
                    message = "安卓 12 以上需要单独授予「闹钟和提醒」权限，否则提醒时间可能不准。现在去开启？",
                    confirmText = "去开启",
                    cancelText = "以后再说",
                    onCancel = { finish() },
                    onConfirm = {
                        Permissions.exactAlarmIntent(this@TaskEditActivity)
                            ?.let { runCatching { startActivity(it) } }
                        finish()
                    }
                ).show()
            } else {
                // 同步成功的也说一声（日历尤其：之前只默默写日历，用户看不到任何回执）
                val synced = buildList {
                    if (wantClock && linkResults.first) add(getString(R.string.link_clock))
                    if (wantCalendar && linkResults.second) add(getString(R.string.link_calendar))
                }
                val syncMsg = if (synced.isEmpty()) ""
                else getString(R.string.synced_fmt, synced.joinToString("、"))
                Toast.makeText(
                    this@TaskEditActivity,
                    "已保存，提醒时间：${previewSummary()}$syncMsg",
                    Toast.LENGTH_LONG
                ).show()
                finish()
            }
        }
    }

    private fun previewSummary(): String {
        if (timeSpecActive) {
            val ts = if (timeMode == TIME_FIXED) TimeSpec.encodeFixed(fixedTimes)
            else TimeSpec.encodeInterval(ivStart, ivStep, ivEnd)
            val d = TimeSpec.display(ts)
            if (d.isNotBlank()) return d
        }
        val parts = ArrayList<String>()
        val dl = anchorMin
        if (preOffset >= 0) parts.add("${Task.formatMin(dl - preOffset)} 开始")
        if (postOffset >= 0) parts.add("${Task.formatMin(dl + postOffset)} 截止")
        return if (parts.isEmpty()) "无响铃" else parts.joinToString("、")
    }

    private fun confirmDelete() {
        com.norbury.taktu.sheet.ConfirmSheet(
            this,
            title = "删除任务",
            message = "删除后这条任务的所有提醒都会取消，确定吗？",
            confirmText = "删除",
            onConfirm = {
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        val dao = AppDatabase.get(this@TaskEditActivity).taskDao()
                        dao.getById(taskId)?.let { dao.delete(it) }
                        MiLinkBridge.removeFromCalendar(this@TaskEditActivity, taskId)
                    }
                    AlarmScheduler.cancel(this@TaskEditActivity, taskId)
                    finish()
                }
            }
        ).show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
