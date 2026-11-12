package com.norbury.taskalarm.sheet

import android.app.Activity
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.norbury.taskalarm.R
import com.norbury.taskalarm.util.ButtonTuner
import com.norbury.taskalarm.util.CalendarGrid
import com.norbury.taskalarm.util.LunarCalendar
import com.norbury.taskalarm.util.Ui
import java.util.Calendar

/**
 * 日期选择结果：长期 / 公历日 / 农历日。
 *
 * 统一「重复时间段」模型（用户 2026-10-20 定案）里，开始时间与结束时间都走这个弹窗：
 *  - [Solar]：公历某一天（yyyy-MM-dd）
 *  - [Lunar]：农历某一天；[year] = 0 表示只选月日（农历任务的开始日按年重复，
 *    库里只存 "MM-dd"）；year > 0 = 结束日期（带年，换算成公历落库）
 *  - [Never]：不结束（永久）
 */
sealed class DayPick {
    object Never : DayPick()
    data class Solar(val key: String) : DayPick()
    data class Lunar(val year: Int, val month: Int, val day: Int) : DayPick()
}

/**
 * 日期选择底部弹窗（替换系统 DatePickerDialog）。
 *
 * 与全站视觉一致：暖白底、陶土橘强调、朱雀仿宋、24dp 顶部圆角、全高展开。
 * 结构：标题 + 历法切换（公历 / 农历）+ 选择区 + 取消/确定。
 *
 *  - **公历**：月份切换（‹ 2026年10月 ›）+ 星期表头 + 6×7 月历
 *    （复用计划页月历的同一套规则：补格只占位，一个月只有一个「1 号」；
 *    今天描边、选中实心主色；周末文字用次要色）。
 *  - **农历**：一体化联动滚轮 —— 左边选月份、右边选第几天（操作方式同时钟选时/分），
 *    结束日期场景（[withYear]）左侧再多一个年轮；月/年变化时日期滚轮跟着重建
 *    （小月没有三十），并实时显示换算出的公历日期。
 *  - [allowNever]：结束日期场景多一个「设为长期」（永久 = 不设结束时间）。
 */
class DateSheet(
    private val activity: Activity,
    private val title: String,
    initial: DayPick,
    /** 结束日期场景：农历选择带年轮（开始日的农历任务按年重复，不需要年） */
    private val withYear: Boolean = false,
    /** 结束日期场景：提供「设为长期」选项（永久） */
    private val allowNever: Boolean = false,
    private val onPicked: (DayPick) -> Unit
) : BottomSheetDialog(activity) {

    private val d = activity.resources.displayMetrics.density
    private val itemPx = (44 * d).toInt()

    /** true = 农历模式；false = 公历模式 */
    private var lunarMode: Boolean = initial is DayPick.Lunar

    /* ---- 公历态 ---- */
    private var selectedKey: String = (initial as? DayPick.Solar)?.key ?: todayKey()
    private var calYear: Int
    private var calMonth0: Int

    /* ---- 农历态 ---- */
    private var lunarYear: Int
    private var lunarMonth: Int
    private var lunarDay: Int

    private lateinit var contentBox: FrameLayout
    private lateinit var tvMonth: TextView
    private lateinit var grid: GridLayout
    private lateinit var lunarBox: LinearLayout
    private lateinit var tvLunarHint: TextView
    private lateinit var dayWheel: WheelView

    /** 农历月日的天数参考年：带年轮用选中的年，开始日用当前农历年 */
    private val refYear: Int
        get() = if (withYear) lunarYear
        else LunarCalendar.solarToLunar(java.time.LocalDate.now())?.first
            ?: java.time.LocalDate.now().year

    private val monthValues = (1..12).map { LunarCalendar.monthName(it) }

    init {
        val cal = Calendar.getInstance()
        val parts = selectedKey.split("-").mapNotNull { it.toIntOrNull() }
        if (parts.size == 3) cal.set(parts[0], parts[1] - 1, parts[2])
        calYear = cal.get(Calendar.YEAR)
        calMonth0 = cal.get(Calendar.MONTH)

        val thisYear = java.time.LocalDate.now().year
        val initL = initial as? DayPick.Lunar
        lunarYear = if (withYear) (initL?.year?.takeIf { it > 0 } ?: thisYear) else thisYear
        lunarMonth = (initL?.month ?: 1).coerceIn(1, 12)
        lunarDay = (initL?.day ?: 1).coerceIn(1, 30)

        buildContent()
    }

    /* ---------------- 界面 ---------------- */

    private fun buildContent() {
        val accent = ContextCompat.getColor(activity, R.color.accent)
        val textSub = ContextCompat.getColor(activity, R.color.app_text_sub)

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding((20 * d).toInt(), (4 * d).toInt(), (20 * d).toInt(), (12 * d).toInt())
        }

        // 标题
        root.addView(TextView(activity).apply {
            text = title
            textSize = 19f
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(activity, R.color.app_text))
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = (12 * d).toInt() })

        // 历法切换：公历 / 农历（开始与结束日期都可各自选历法）
        val chipSolar = MaterialButton(
            ContextThemeWrapper(activity, R.style.ThemeOverlay_TaskAlarm_SheetButtonChip)
        ).apply { text = activity.getString(R.string.anchor_solar) }
        val chipLunar = MaterialButton(
            ContextThemeWrapper(activity, R.style.ThemeOverlay_TaskAlarm_SheetButtonChip)
        ).apply { text = activity.getString(R.string.anchor_lunar) }
        root.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(chipSolar)
            addView(chipLunar, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = (10 * d).toInt() })
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = (12 * d).toInt() })
        chipSolar.setOnClickListener { if (lunarMode) { lunarMode = false; refreshMode(chipSolar, chipLunar) } }
        chipLunar.setOnClickListener { if (!lunarMode) { lunarMode = true; refreshMode(chipSolar, chipLunar) } }

        // 选择区（公历月历 / 农历联动滚轮二选一）
        contentBox = FrameLayout(activity)
        root.addView(contentBox, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        buildSolarView()
        buildLunarView()

        // 底部：取消 / 确定（结束日期场景左侧多一个「设为长期」）
        val btnCancel = MaterialButton(
            ContextThemeWrapper(activity, R.style.ThemeOverlay_TaskAlarm_SheetButtonText)
        ).apply {
            text = activity.getString(R.string.sheet_action_cancel)
            setTextColor(textSub)
        }
        val btnOk = MaterialButton(activity).apply {
            text = activity.getString(R.string.sheet_action_ok)
        }
        val btnNever = MaterialButton(
            ContextThemeWrapper(activity, R.style.ThemeOverlay_TaskAlarm_SheetButtonText)
        ).apply {
            text = activity.getString(R.string.day_pick_never)
            setTextColor(accent)
            visibility = if (allowNever) View.VISIBLE else View.GONE
        }
        root.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            addView(btnNever)
            addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))   // 把取消/确定推到右边
            addView(btnCancel)
            addView(btnOk, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = (8 * d).toInt() })
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (16 * d).toInt() })

        btnCancel.setOnClickListener { dismiss() }
        btnNever.setOnClickListener { onPicked(DayPick.Never); dismiss() }
        btnOk.setOnClickListener {
            onPicked(
                if (lunarMode) DayPick.Lunar(
                    if (withYear) lunarYear else 0, lunarMonth, lunarDay
                )
                else DayPick.Solar(selectedKey)
            )
            dismiss()
        }

        // 内容偏高（月历 6 行），套一层滚动兜底小屏幕
        val scroll = ScrollView(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            addView(root)
        }
        // v3.34.90：日期弹窗同样走毛玻璃
        setContentView(Sheets.frost(activity, scroll))
        ButtonTuner.tune(root)

        refreshMode(chipSolar, chipLunar)

        // 弹窗一律展开到全高，不做「半档」态
        setOnShowListener { dialogInterface ->
            Sheets.forceExpanded(dialogInterface as BottomSheetDialog)
        }
    }

    /* ---- 公历月历 ---- */

    private fun buildSolarView() {
        val btnPrev = ImageButton(activity).apply {
            setImageResource(R.drawable.ic_back)
            background = ContextCompat.getDrawable(activity, R.drawable.bg_circle_button)
            val p = (10 * d).toInt()
            setPadding(p, p, p, p)
            scaleType = android.widget.ImageView.ScaleType.CENTER
            imageTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(activity, R.color.app_text)
            )
            contentDescription = activity.getString(R.string.cal_prev)
            setOnClickListener { shiftMonth(-1) }
        }
        val btnNext = ImageButton(activity).apply {
            setImageResource(R.drawable.ic_back)
            rotation = 180f
            background = ContextCompat.getDrawable(activity, R.drawable.bg_circle_button)
            val p = (10 * d).toInt()
            setPadding(p, p, p, p)
            scaleType = android.widget.ImageView.ScaleType.CENTER
            imageTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(activity, R.color.app_text)
            )
            contentDescription = activity.getString(R.string.cal_next)
            setOnClickListener { shiftMonth(1) }
        }
        tvMonth = TextView(activity).apply {
            textSize = 19f
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(activity, R.color.app_text))
        }
        val nav = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(btnPrev)
            addView(tvMonth, LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ))
            addView(btnNext, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = (8 * d).toInt() })
        }

        val weekHead = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        listOf(
            R.string.week_sun, R.string.week_mon, R.string.week_tue, R.string.week_wed,
            R.string.week_thu, R.string.week_fri, R.string.week_sat
        ).forEach { res ->
            weekHead.addView(TextView(activity).apply {
                text = activity.getString(res)
                textSize = 11.5f
                gravity = Gravity.CENTER
                setTextColor(ContextCompat.getColor(activity, R.color.app_text_sub))
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }

        grid = GridLayout(activity).apply {
            columnCount = 7
            rowCount = 6
        }

        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(nav, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (10 * d).toInt() })
            addView(weekHead, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (2 * d).toInt() })
            addView(grid, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }
        contentBox.addView(box, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ))
        renderMonth()
    }

    private fun renderMonth() {
        tvMonth.text = CalendarGrid.title(calYear, calMonth0)
        val today = todayKey()
        grid.removeAllViews()
        CalendarGrid.cells(calYear, calMonth0).forEach { cell ->
            grid.addView(buildCell(cell, cell.dateKey == selectedKey, cell.dateKey == today))
        }
    }

    /**
     * 一格日期。补格（上下月）只占位撑住网格，不显示、不可点 ——
     * 与计划页月历同一口径：一个月只有一个「1 号」。
     */
    private fun buildCell(cell: CalendarGrid.Cell, selected: Boolean, isToday: Boolean): View {
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED),
                GridLayout.spec(GridLayout.UNDEFINED, 1f)
            ).apply {
                width = 0
                height = (46 * d).toInt()
            }
        }
        if (!cell.inMonth) return box

        val accent = ContextCompat.getColor(activity, R.color.accent)
        val accentOn = ContextCompat.getColor(activity, R.color.accent_on)
        val textSub = ContextCompat.getColor(activity, R.color.app_text_sub)
        val size = (32 * d).toInt()
        val dow = CalendarGrid.dowOf(cell.dateKey)
        val weekend = dow == Calendar.SATURDAY || dow == Calendar.SUNDAY

        val num = TextView(activity).apply {
            text = cell.day.toString()
            textSize = 14f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(size, size)
            background = when {
                selected -> oval(accent)
                isToday -> oval(0, (1.5 * d).toInt(), accent)
                else -> null
            }
            setTextColor(
                when {
                    selected -> accentOn
                    isToday -> accent
                    weekend -> textSub
                    else -> ContextCompat.getColor(activity, R.color.app_text)
                }
            )
        }
        box.addView(num)
        box.setOnClickListener {
            selectedKey = cell.dateKey
            renderMonth()
        }
        return box
    }

    private fun oval(fill: Int, strokeW: Int = 0, strokeColor: Int = 0) =
        android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            if (fill != 0) setColor(fill)
            if (strokeW > 0) setStroke(strokeW, strokeColor)
        }

    /* ---- 农历联动滚轮 ---- */

    private fun buildLunarView() {
        val accentContainer = ContextCompat.getColor(activity, R.color.accent_container)
        val textSub = ContextCompat.getColor(activity, R.color.app_text_sub)

        lunarBox = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        tvLunarHint = TextView(activity).apply {
            textSize = 12.5f
            gravity = Gravity.CENTER
            setTextColor(textSub)
        }
        lunarBox.addView(tvLunarHint, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = (10 * d).toInt() })

        // 选中光带垫在底下，滚轮浮在上面（与 TimeSheet 同一套视觉）
        val wheelW = (72 * d).toInt()
        val band = View(activity).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                setColor(accentContainer)
                cornerRadius = (12 * d).toFloat()
            }
        }
        val wheels = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val wheelH = itemPx * 5

        var yearWheel: WheelView? = null
        var monthWheel: WheelView? = null
        if (withYear) {
            val years = (lunarYear..lunarYear + 30).map { "$it 年" }
            yearWheel = WheelView(activity, years, 0) { idx ->
                lunarYear = years[idx].removeSuffix(" 年").toIntOrNull() ?: lunarYear
                rebuildDayWheel()
                refreshLunarHint()
            }
            wheels.addView(yearWheel, LinearLayout.LayoutParams(wheelW, wheelH))
        }
        monthWheel = WheelView(activity, monthValues, lunarMonth - 1) { idx ->
            lunarMonth = idx + 1
            rebuildDayWheel()
            refreshLunarHint()
        }
        dayWheel = newDayWheel(lunarDay)
        wheels.addView(monthWheel, LinearLayout.LayoutParams(wheelW, wheelH))
        wheels.addView(dayWheel, LinearLayout.LayoutParams(wheelW, wheelH))

        val bandW = wheelW * (if (withYear) 3 else 2)
        lunarBox.addView(FrameLayout(activity).apply {
            addView(band, FrameLayout.LayoutParams(bandW, itemPx, Gravity.CENTER))
            addView(
                wheels,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
            )
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        contentBox.addView(lunarBox, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ))
        refreshLunarHint()
    }

    /** 当月天数的日期滚轮（小月没有三十）；月/年变化时整只重建，选中日落进区间 */
    private fun newDayWheel(selectedDay: Int): WheelView {
        val days = daysInLunarMonth()
        val dayValues = (1..days).map { LunarCalendar.dayName(it) }
        return WheelView(activity, dayValues, (selectedDay - 1).coerceIn(dayValues.indices)) { idx ->
            lunarDay = idx + 1
            refreshLunarHint()
        }
    }

    /**
     * 换月/换年：日期滚轮跟着重建（天数 29/30 变了），尽量保住原选日期。
     *
     * **必须把原 LayoutParams 带回新滚轮**：月/日两列是固定尺寸
     * （LinearLayout.LayoutParams(72dp, 220dp)）。addView 不带 lp 时新
     * RecyclerView 拿到默认 WRAP_CONTENT，而 WheelView 是循环轮盘
     * （真实天数 × LOOP 倍，约 2900 格）——高度 wrap_content 下
     * autoMeasure 会全量测量，日期列当场膨胀到占满整页。
     */
    private fun rebuildDayWheel() {
        val keep = lunarDay
        val parent = dayWheel.parent as? ViewGroup ?: return
        val index = parent.indexOfChild(dayWheel)
        val lp = dayWheel.layoutParams ?: LinearLayout.LayoutParams(
            (72 * d).toInt(), itemPx * 5
        )
        parent.removeViewAt(index)
        dayWheel = newDayWheel(keep)
        parent.addView(dayWheel, index, lp)
    }

    /** 农历参考年的月天数（29/30）：直接问换算表，没有三十就返回 29 */
    private fun daysInLunarMonth(): Int =
        if (LunarCalendar.lunarToSolar(refYear, lunarMonth, 30) != null) 30 else 29

    /** 实时显示换算结果：开始日看「今年公历」，结束日看「公历」 */
    private fun refreshLunarHint() {
        val solar = LunarCalendar.lunarToSolar(refYear, lunarMonth, lunarDay)
        tvLunarHint.text = when {
            solar == null -> activity.getString(R.string.day_pick_lunar_invalid)
            withYear -> activity.getString(R.string.day_pick_lunar_convert_fmt, solar.toString())
            else -> activity.getString(
                R.string.day_pick_lunar_today_fmt, solar.toString()
            )
        }
    }

    /* ---------------- 模式切换 ---------------- */

    private fun refreshMode(chipSolar: MaterialButton, chipLunar: MaterialButton) {
        Ui.chip(chipSolar, !lunarMode)
        Ui.chip(chipLunar, lunarMode)
        (contentBox.getChildAt(0) as? View)?.visibility = if (lunarMode) View.GONE else View.VISIBLE
        (contentBox.getChildAt(1) as? View)?.visibility = if (lunarMode) View.VISIBLE else View.GONE
    }

    /* ---------------- 日期工具 ---------------- */

    private fun todayKey(): String {
        val cal = Calendar.getInstance()
        return "%04d-%02d-%02d".format(
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH)
        )
    }

    /** 翻月；跨年由 Calendar 自己推算，不会出现 13 月 */
    private fun shiftMonth(delta: Int) {
        val cal = Calendar.getInstance().apply {
            set(calYear, calMonth0, 1)
            add(Calendar.MONTH, delta)
        }
        calYear = cal.get(Calendar.YEAR)
        calMonth0 = cal.get(Calendar.MONTH)
        renderMonth()
    }
}
