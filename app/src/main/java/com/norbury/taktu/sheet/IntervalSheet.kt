package com.norbury.taktu.sheet

import android.app.Activity
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.norbury.taktu.R
import com.norbury.taktu.util.ButtonTuner

/**
 * 循环间隔选择底部弹窗（「每天时刻 → 间隔循环」的每隔多少分钟）。
 *
 * 单滚盘 1..1440 分钟（1 分钟 ~ 24 小时），文案智能格式化：
 *  - < 60 分钟 → "45 分"；整小时 → "2 小时"；其余 → "1 小时 30 分"；上限 → "24 小时"。
 *
 * 为什么不用固定档位列表：早期是 5 分钟步进的 8 个预设值（5..240），
 * 用户要 25 分钟、90 分钟这类值根本选不到 —— 间隔本来就该是任意分钟。
 *
 * 视觉与 [TimeSheet] 同一套：选中光带垫底、单轮盘浮上、44dp 一格、5 格视口、
 * 循环滚动（1440 格 × LOOP 倍虚拟数据，任何方向不撞边界）。
 */
class IntervalSheet(
    private val activity: Activity,
    private val initialMin: Int,
    private val onPicked: (Int) -> Unit
) : BottomSheetDialog(activity) {

    companion object {
        /** 间隔下限 / 上限（分钟）：1 分钟 ~ 24 小时 */
        const val MIN_MIN = 1
        const val MAX_MIN = 1440

        /** 间隔的人话文案：45 分 / 2 小时 / 1 小时 30 分 / 24 小时 */
        fun label(min: Int): String {
            val v = min.coerceIn(MIN_MIN, MAX_MIN)
            val h = v / 60
            val m = v % 60
            return when {
                h == 0 -> "$m 分"
                m == 0 -> "$h 小时"
                else -> "$h 小时 $m 分"
            }
        }
    }

    private val d = activity.resources.displayMetrics.density
    private val itemPx = (44 * d).toInt()

    private lateinit var wheel: WheelView

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
            text = "循环间隔"
            textSize = 19f
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(activity, R.color.app_text))
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = (4 * d).toInt() })

        // 副标题：说明这一天内每隔这么久响一次
        root.addView(TextView(activity).apply {
            text = "一天内每隔多久响一次"
            textSize = 12.5f
            gravity = Gravity.CENTER
            setTextColor(textSub)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = (10 * d).toInt() })

        // 轮盘区：选中光带垫在底下，单滚轮浮在上面
        val wheelW = (108 * d).toInt()
        val band = View(activity).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                setColor(accentContainer)
                cornerRadius = (12 * d).toFloat()
            }
        }
        val values = (MIN_MIN..MAX_MIN).map { label(it) }
        wheel = WheelView(activity, values, initialMin.coerceIn(MIN_MIN, MAX_MIN) - MIN_MIN)

        root.addView(FrameLayout(activity).apply {
            addView(band, FrameLayout.LayoutParams(wheelW, itemPx, Gravity.CENTER))
            addView(
                wheel,
                FrameLayout.LayoutParams(wheelW, itemPx * 5, Gravity.CENTER)
            )
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // 取消（文字钮）/ 确定（实心钮）
        val btnCancel = MaterialButton(
            ContextThemeWrapper(activity, R.style.ThemeOverlay_Taktu_SheetButtonText)
        ).apply {
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
            onPicked(wheel.selectedIndex + MIN_MIN)
            dismiss()
        }

        setContentView(root)
        ButtonTuner.tune(root)

        // 与全站一致：展开到全高，轮盘不被压扁
        setOnShowListener { dialogInterface ->
            Sheets.forceExpanded(dialogInterface as BottomSheetDialog)
        }
    }
}
