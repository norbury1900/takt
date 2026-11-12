package com.norbury.taskalarm.sheet

import android.app.Activity
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.norbury.taskalarm.R

/**
 * 月份选择底部弹窗（计划页用，替代旧 AlertDialog 版 —— 那个底色与主题打架）。
 * 与全站弹窗同视觉：暖白卡片底、陶土橘选中、24dp 顶部圆角（样式挂在主题 overlay 上）。
 * 结构：‹ 年份 ›（与计划页翻月箭头同款）+ 4×3 月份格；点月份立即回调并关闭。
 */
class MonthSheet(
    private val activity: Activity,
    initialYear: Int,
    initialMonth0: Int,
    /** 月份格列数：计划页 4×3；新建任务「年」用 3×4 */
    private val columns: Int = 4,
    private val onPicked: (year: Int, month0: Int) -> Unit
) : BottomSheetDialog(activity) {

    private val d = activity.resources.displayMetrics.density
    private var year = initialYear
    private val selYear = initialYear
    private val selMonth0 = initialMonth0
    private lateinit var tvYear: TextView
    private lateinit var grid: GridLayout

    init {
        buildContent()
    }

    private fun buildContent() {
        val pad = (20 * d).toInt()
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(pad, (4 * d).toInt(), pad, (16 * d).toInt())
        }

        // 年份左右切换：与计划页翻月箭头同款（v3.34.128）
        fun yearBtn(delta: Int, rotated: Boolean) = ImageButton(activity).apply {
            setImageResource(R.drawable.ic_chevron_left)
            rotationY = if (rotated) 180f else 0f
            val size = (38 * d).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size)
            val p = (10 * d).toInt()
            setPadding(p, p, p, p)
            scaleType = ImageView.ScaleType.FIT_CENTER
            imageTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(activity, R.color.app_text_sub)
            )
            val tv = android.util.TypedValue()
            if (activity.theme.resolveAttribute(
                    android.R.attr.selectableItemBackgroundBorderless, tv, true
                )
            ) background = ContextCompat.getDrawable(activity, tv.resourceId)
            setOnClickListener { year += delta; renderGrid() }
        }
        tvYear = TextView(activity).apply {
            textSize = 19f
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(activity, R.color.app_text))
        }
        root.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(yearBtn(-1, rotated = false))
            addView(tvYear, LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ))
            addView(yearBtn(1, rotated = true))
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = (10 * d).toInt() })

        grid = GridLayout(activity).apply { columnCount = columns }
        root.addView(grid)
        renderGrid()

        val scroll = ScrollView(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            addView(root)
        }
        setContentView(scroll)
        setOnShowListener { Sheets.forceExpanded(it as BottomSheetDialog) }
    }

    private fun renderGrid() {
        tvYear.text = "${year}年"
        grid.removeAllViews()
        val accent = ContextCompat.getColor(activity, R.color.accent)
        val accentOn = ContextCompat.getColor(activity, R.color.accent_on)
        val card = ContextCompat.getColor(activity, R.color.app_card_hi)
        val textMain = ContextCompat.getColor(activity, R.color.app_text)
        val tv = android.util.TypedValue()
        val ripple = if (activity.theme.resolveAttribute(
                android.R.attr.selectableItemBackground, tv, true
            )
        ) ContextCompat.getDrawable(activity, tv.resourceId) else null

        for (m in 0..11) {
            val selected = year == selYear && m == selMonth0
            val cell = TextView(activity).apply {
                text = "${m + 1}月"
                textSize = 14f
                gravity = Gravity.CENTER
                layoutParams = GridLayout.LayoutParams(
                    GridLayout.spec(GridLayout.UNDEFINED),
                    GridLayout.spec(GridLayout.UNDEFINED, 1f)
                ).apply {
                    width = 0
                    height = (44 * d).toInt()
                    topMargin = (6 * d).toInt()
                    leftMargin = (3 * d).toInt()
                    rightMargin = (3 * d).toInt()
                }
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 10 * d
                    // 非选中态纯实底、无描边（用户 2026-10-21：外轮框太丑，与全站
                    // TagSheets 的实底做法对齐；选中/非选中靠 accent 与 card_hi 区分）
                    setColor(if (selected) accent else card)
                }
                foreground = ripple
                setTextColor(if (selected) accentOn else textMain)
                setOnClickListener {
                    onPicked(year, m)
                    dismiss()
                }
            }
            grid.addView(cell)
        }
    }
}
