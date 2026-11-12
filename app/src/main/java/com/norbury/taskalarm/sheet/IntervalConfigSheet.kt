package com.norbury.taskalarm.sheet

import android.app.Activity
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.norbury.taskalarm.R
import com.norbury.taskalarm.data.Task
import com.norbury.taskalarm.util.ButtonTuner

/**
 * 「间隔循环」参数的弹窗选择（v3.34.89）。
 *
 * 以前开始 / 间隔 / 响到 是三个并排 chip 摊在页面上，一是挤、二是每个都要单独点开；
 * 现在收进一个弹窗：三行 = 一个字段，点一行打开对应的滚轮，选完即时回写。
 *
 * @param onChanged (startMin, stepMin, endMin) —— 每次字段变化都回调，调用方负责重绘
 */
class IntervalConfigSheet(
    private val activity: Activity,
    private var start: Int,
    private var step: Int,
    private var end: Int,
    private val onChanged: (Int, Int, Int) -> Unit
) : BottomSheetDialog(activity) {

    private val d = activity.resources.displayMetrics.density

    private lateinit var startBtn: MaterialButton
    private lateinit var stepBtn: MaterialButton
    private lateinit var endBtn: MaterialButton

    init {
        buildContent()
    }

    private fun buildContent() {
        val textSub = ContextCompat.getColor(activity, R.color.app_text_sub)

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding((20 * d).toInt(), (4 * d).toInt(), (20 * d).toInt(), (14 * d).toInt())
        }

        root.addView(TextView(activity).apply {
            text = activity.getString(R.string.time_mode_interval)
            textSize = 19f
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(activity, R.color.app_text))
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = (10 * d).toInt() })

        startBtn = valueButton { pickStart() }
        stepBtn = valueButton { pickStep() }
        endBtn = valueButton { pickEnd() }

        root.addView(fieldRow("开始时刻", startBtn))
        root.addView(fieldRow("循环间隔", stepBtn))
        root.addView(fieldRow("响到", endBtn))

        root.addView(TextView(activity).apply {
            text = "到点自动响；星期 / 日期规则照常生效，全天不响的时刻不会生成"
            textSize = 12f
            setTextColor(textSub)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (6 * d).toInt() })

        val done = MaterialButton(activity).apply {
            text = activity.getString(R.string.sheet_action_ok)
        }
        done.setOnClickListener { dismiss() }
        root.addView(done, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (16 * d).toInt() })

        setContentView(Sheets.frost(activity, root))
        ButtonTuner.tune(root)
        refresh()

        setOnShowListener { Sheets.forceExpanded(it as BottomSheetDialog) }
    }

    private fun fieldRow(label: String, value: MaterialButton): LinearLayout =
        LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (6 * d).toInt(), 0, (6 * d).toInt())
            addView(TextView(activity).apply {
                text = label
                textSize = 14f
                setTextColor(ContextCompat.getColor(activity, R.color.app_text))
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(value)
        }

    private fun valueButton(onClick: () -> Unit): MaterialButton =
        MaterialButton(
            activity, null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            isAllCaps = false
            insetTop = 0
            insetBottom = 0
            layoutParams = LinearLayout.LayoutParams(
                (110 * d).toInt(), (44 * d).toInt()
            )
            setOnClickListener { onClick() }
        }

    private fun pickStart() {
        TimeSheet(activity, "开始时刻", start) { m ->
            start = m
            if (end < start) end = m
            refresh()
        }.show()
    }

    private fun pickStep() {
        IntervalSheet(activity, step) { m ->
            step = m
            refresh()
        }.show()
    }

    private fun pickEnd() {
        TimeSheet(activity, "响到", end) { m ->
            end = m
            if (end < start) start = m
            refresh()
        }.show()
    }

    private fun refresh() {
        startBtn.text = Task.formatMin(start)
        stepBtn.text = IntervalSheet.label(step)
        endBtn.text = activity.getString(R.string.iv_end_fmt, Task.formatMin(end))
        onChanged(start, step, end)
    }
}
