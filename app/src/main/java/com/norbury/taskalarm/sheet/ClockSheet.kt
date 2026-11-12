package com.norbury.taskalarm.sheet

import android.app.Activity
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.norbury.taskalarm.R
import com.norbury.taskalarm.data.ClockOffset
import com.norbury.taskalarm.data.Task
import com.norbury.taskalarm.util.ButtonTuner

/**
 * **带范围**的钟点选择弹窗（v3.34.17）。
 *
 * 与 [TimeSheet] 的区别：TimeSheet 允许选 00:00–23:55 的任意时刻，
 * 这里只允许选 [minMin, maxMin] 区间内的钟点 —— 用于「开始时间 / 截止时间」
 * 这类**有语义边界**的字段：
 *   - 开始时间（催促点）：不能晚于目标时刻；
 *   - 截止时间（复查点）：不能早于目标时刻。
 *
 * 范围裁剪到**分钟档**（5 分钟一档）：
 *   - 小时滚轮只列出「该小时内至少有一档可选分钟」的小时；
 *   - 分钟滚轮随所选小时重建 —— 例如范围 00:00–08:30 时，
 *     8 点这一小时只能选 00/05/…/30，9 点整档不可选。
 *
 * 顶部一个「关闭此提醒」开关：对应落库的 -1（关闭），
 * 0（准时）= 选的目标时刻本身，不需要单独一档。
 *
 * @param initiallyOff 初始是否处于「关闭」态（老任务 offset = -1 时要回显为开）
 * @param onPicked 选中的当日分钟数；关闭时传 [Task.OFFSET_OFF]（-1）
 */
class ClockSheet(
    private val activity: Activity,
    private val title: String,
    private val initialMin: Int,
    private val minMin: Int,
    private val maxMin: Int,
    private val initiallyOff: Boolean = false,
    private val onPicked: (Int) -> Unit
) : BottomSheetDialog(activity) {

    private val d = activity.resources.displayMetrics.density
    private val itemPx = (44 * d).toInt()

    /** 范围内可选的小时（真实值，非下标） */
    private val hours: List<Int> = ClockOffset.validHours(minMin, maxMin)

    /** 分钟滚轮当前对应的小时 —— 变了才重建（初始 settle 不算改动） */
    private var currentHour = 0

    private lateinit var hourWheel: WheelView
    private lateinit var minuteWheel: WheelView
    private lateinit var offSwitch: MaterialSwitch

    init {
        buildContent()
    }

    /* ---------------- 界面 ---------------- */

    private fun buildContent() {
        val accentContainer = ContextCompat.getColor(activity, R.color.accent_container)
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
        ).apply { bottomMargin = (6 * d).toInt() })

        // 范围提示：把这个字段能选的时间段说清楚，避免用户选到无效值
        val rangeText = if (hours.isEmpty()) {
            activity.getString(R.string.clock_range_empty)
        } else {
            activity.getString(
                R.string.clock_range_fmt,
                Task.formatMin(minMin),
                Task.formatMin(maxMin)
            )
        }
        root.addView(TextView(activity).apply {
            text = rangeText
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(textSub)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = (10 * d).toInt() })

        // 轮盘区：光带垫底，两个轮盘 + 冒号浮在上面
        val wheelW = (64 * d).toInt()
        val band = View(activity).apply {
            background = rounded(accentContainer, (12 * d).toInt())
        }
        val bandWidth = (wheelW * 2 + 12 * d).toInt()
        val wheels = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val initHour = hours.indexOfFirst { it == (initialMin / 60) }.takeIf { it >= 0 } ?: 0
        currentHour = hours.getOrElse(initHour) { 0 }
        hourWheel = WheelView(activity, hours.map { "%02d".format(it) }, initHour) { idx ->
            val h = hours.getOrElse(idx) { return@WheelView }
            if (h != currentHour) {
                currentHour = h
                rebuildMinuteWheel()
            }
        }
        minuteWheel = newMinuteWheel(initialMin)
        wheels.addView(hourWheel, LinearLayout.LayoutParams(wheelW, itemPx * 5))
        wheels.addView(TextView(activity).apply {
            text = ":"
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(textSub)
        }, LinearLayout.LayoutParams((12 * d).toInt(), itemPx * 5))
        wheels.addView(minuteWheel, LinearLayout.LayoutParams(wheelW, itemPx * 5))

        root.addView(FrameLayout(activity).apply {
            addView(band, FrameLayout.LayoutParams(bandWidth, itemPx, Gravity.CENTER))
            addView(
                wheels,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
            )
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // 关闭开关（对应落库的 -1）
        offSwitch = MaterialSwitch(activity).apply {
            text = activity.getString(R.string.clock_off_switch)
            isChecked = initiallyOff
            setTextColor(ContextCompat.getColor(activity, R.color.app_text))
        }
        root.addView(offSwitch, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = (14 * d).toInt()
            bottomMargin = (2 * d).toInt()
        })

        // 取消（文字钮）/ 确定（实心钮）
        val btnCancel = styledButton(R.style.ThemeOverlay_TaskAlarm_SheetButtonText).apply {
            text = activity.getString(R.string.sheet_action_cancel)
            setTextColor(textSub)
        }
        val btnOk = MaterialButton(activity).apply {
            text = activity.getString(R.string.sheet_action_ok)
        }

        root.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
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
        btnOk.setOnClickListener {
            onPicked(if (offSwitch.isChecked) Task.OFFSET_OFF else pickedMin())
            dismiss()
        }

        // v3.34.89：弹窗内容包进毛玻璃，不再透出后面的页面
        val frosted = Sheets.frost(activity, root)
        setContentView(frosted)
        ButtonTuner.tune(frosted)

        setOnShowListener { dialogInterface ->
            Sheets.forceExpanded(dialogInterface as BottomSheetDialog)
        }
    }

    /** 当前选中的完整钟点（当日分钟数） */
    private fun pickedMin(): Int {
        val h = currentHour
        val slots = slotsOf(h)
        val m = slots.getOrElse(minuteWheel.selectedIndex) { slots.firstOrNull() ?: 0 }
        return h * 60 + m
    }

    /**
     * 某小时的合法分钟档 —— **返回该小时内的分数（0..59）**，直接当分针显示。
     *
     * v3.34.21 修 bug：以前这里调 [ClockOffset.minuteSlots] 拿的是**绝对分钟**
     * （11 点这一档返回 660..715），滚轮直接显示成「660/665/…」，用户看到「分钟 680」。
     */
    private fun slotsOf(hour: Int): List<Int> =
        ClockOffset.minuteSlotsOfHour(hour, minMin, maxMin)

    /** 分钟滚轮：按当前小时重建 */
    private fun newMinuteWheel(pickedMin: Int): WheelView {
        val slots = slotsOf(currentHour)
        val want = (pickedMin % 60).coerceIn(0, 59)
        val idx = slots.indexOfFirst { it >= want }.takeIf { it >= 0 }
            ?: slots.indexOfLast { it <= want }.takeIf { it >= 0 }
            ?: 0
        return WheelView(activity, slots.map { "%02d".format(it) }, idx)
    }

    /**
     * 小时变了 → 分钟列表跟着变，整只重建。
     * **必须把原 LayoutParams 带回**（v3.34.2 教训：动态重建 RecyclerView 子 view
     * 不回传 LayoutParams，循环轮盘在 wrap_content 高度下会撑爆布局）。
     */
    private fun rebuildMinuteWheel() {
        val parent = minuteWheel.parent as? ViewGroup ?: return
        val index = parent.indexOfChild(minuteWheel)
        val lp = minuteWheel.layoutParams ?: LinearLayout.LayoutParams(
            (64 * d).toInt(), itemPx * 5
        )
        val keep = pickedMinOrZero()
        parent.removeViewAt(index)
        minuteWheel = newMinuteWheel(keep)
        parent.addView(minuteWheel, index, lp)
    }

    /** 重建前记住当前选择（分钟落在新列表外时会被夹到最近的一档） */
    private fun pickedMinOrZero(): Int {
        val h = currentHour
        val slots = slotsOf(h)
        val m = slots.getOrElse(minuteWheel.selectedIndex) { slots.firstOrNull() ?: 0 }
        return h * 60 + m
    }

    private fun rounded(color: Int, radius: Int) =
        android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = radius.toFloat()
        }

    private fun styledButton(overlayRes: Int) =
        MaterialButton(ContextThemeWrapper(activity, overlayRes))
}
