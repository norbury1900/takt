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
 * 时间选择底部弹窗（替换系统 TimePickerDialog）。
 *
 * 视觉语言与全站一致：米白/暖白底、陶土橘强调、朱雀仿宋、10dp 以内小圆角。
 * 结构：标题 + 双轮盘（时 / 分，5 分钟粒度）+ 选中光带 + 取消/确定。
 *
 * 轮盘用 [WheelView]（RecyclerView + LinearSnapHelper 实现的循环轮盘）：
 *  - item 固定 44dp 高，RecyclerView 高 5 格，上下 padding 各 2 格 +
 *    clipToPadding=false —— 滚动停住时落在正中间的就是选中项；
 *  - **循环滚动**：真实值重复 100 遍、起点放中间，任何方向都不会撞边界
 *    （有边界的列表在 `00` 处往上滚不动，正是"分钟拉不了"的根因）；
 *  - 初始项用 scrollToPositionWithOffset(i, 0)：相对 padding 边 0 偏移，
 *    正好落在 5 格视口的正中央。
 */
class TimeSheet(
    private val activity: Activity,
    private val title: String,
    private val initialMin: Int,
    private val onPicked: (Int) -> Unit
) : BottomSheetDialog(activity) {

    private val d = activity.resources.displayMetrics.density
    private val itemPx = (44 * d).toInt()

    /** 5 分钟一格：初始值就近收敛，不会停在一格正中间 */
    private val minuteStep = 5
    private val minutes = (0 until 60 step minuteStep).map { "%02d".format(it) }
    private val hours = (0..23).map { "%02d".format(it) }

    private lateinit var hourWheel: WheelView
    private lateinit var minuteWheel: WheelView

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
        ).apply { bottomMargin = (14 * d).toInt() })

        // 轮盘区：选中光带垫在底下，两个轮盘 + 冒号浮在上面
        val wheelW = (64 * d).toInt()
        val band = View(activity).apply {
            background = rounded(accentContainer, (12 * d).toInt())
        }
        val bandWidth = (wheelW * 2 + 12 * d).toInt()
        val wheels = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        hourWheel = WheelView(activity, hours, ((initialMin / 60).coerceIn(0, 23)))
        minuteWheel = WheelView(activity, minutes, ((initialMin % 60) / minuteStep).coerceIn(0, minutes.size - 1))
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

        // 取消（文字钮）/ 确定（实心钮，走主题 materialButtonStyle = 全局实心款）
        val btnCancel = styledButton(R.style.ThemeOverlay_Taktu_SheetButtonText).apply {
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
            onPicked(hourWheel.selectedIndex * 60 + minuteWheel.selectedIndex * minuteStep)
            dismiss()
        }

        // v3.34.89：弹窗内容包进毛玻璃，不再透出后面的页面
        val frosted = Sheets.frost(activity, root)
        setContentView(frosted)
        ButtonTuner.tune(frosted)

        // 弹窗一律展开到全高，不做「半档」态，避免轮盘被压扁
        setOnShowListener { dialogInterface ->
            Sheets.forceExpanded(dialogInterface as BottomSheetDialog)
        }
    }

    private fun rounded(color: Int, radius: Int) =
        android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = radius.toFloat()
        }

    /** 带指定按钮样式的 MaterialButton（构造函数只认 defStyleAttr，故包一层主题） */
    private fun styledButton(overlayRes: Int) =
        MaterialButton(ContextThemeWrapper(activity, overlayRes))
}
