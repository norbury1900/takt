package com.norbury.taktu.sheet

import android.app.Activity
import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.norbury.taktu.R
import com.norbury.taktu.util.ButtonTuner

/**
 * 标签相关的小弹窗，与 DateSheet / TimeSheet 同一套视觉
 * （暖白底、陶土强调、朱雀仿宋、24dp 顶部圆角、全高展开）。
 *
 * - [TagSheet]：标签下拉选择；点行即选，行尾常驻「改 / 删」小按钮
 *   （长按与左划在小米上都不可靠，最后改成常驻按钮，最稳）。
 * - [WheelSheet]：数字滚轮弹窗（催促 / 复查时机、间隔数量共用）。
 * - [TextSheet]：带输入框的小弹窗（新建 / 重命名标签共用）。
 *   —— 替代系统 AlertDialog，避免默认弹窗底色与主题不搭。
 */
class TagSheet(
    activity: Activity,
    currentTag: String,
    /** 可选标签：(名字, 是否自定义)；内置标签的「删除」= 隐藏，只给删除不给重命名 */
    options: List<Pair<String, Boolean>>,
    private val onPick: (String) -> Unit,
    private val onAdd: () -> Unit,
    private val onRename: (String) -> Unit,
    private val onDelete: (String) -> Unit,
) : BottomSheetDialog(activity) {

    private val d = activity.resources.displayMetrics.density

    init {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * d).toInt(), (4 * d).toInt(), (20 * d).toInt(), (12 * d).toInt())
        }
        root.addView(sheetTitle(activity, activity.getString(R.string.tag_title)),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (4 * d).toInt() })

        // 未分组（tag 为空串）：不可划、不可删
        addRow(root, activity.getString(R.string.group_untagged), currentTag.isBlank()) {
            onPick("")
            dismiss()
        }
        options.forEach { (name, _) ->
            root.addView(tagRow(name, currentTag == name))
        }

        // ＋ 自定义 入口
        root.addView(TextView(activity).apply {
            text = activity.getString(R.string.tag_custom_add)
            textSize = 15f
            setTextColor(ContextCompat.getColor(activity, R.color.accent))
            val p = (14 * d).toInt()
            setPadding(p, p, p, p)
            background = rippleBg(activity)
            setOnClickListener { dismiss(); onAdd() }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // 左划是隐藏手势，给一句提示兜底可发现性
        root.addView(TextView(activity).apply {
            text = activity.getString(R.string.tag_manage_hint)
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(activity, R.color.app_text_sub))
            val p = (8 * d).toInt()
            setPadding(p, p, p, p)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // 与 DateSheet 同构：内容套 ScrollView，标签多时也能滚
        val scroll = ScrollView(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            addView(root)
        }
        setContentView(Sheets.frost(activity, scroll))
        setOnShowListener { Sheets.forceExpanded(this) }
    }

    /**
     * 一行标签：名字占满（点击选中），行尾常驻「改 / 删」两个小字按钮。
     * 内置标签也给改（改名 = 藏原名 + 以新名入库），删 = 隐藏。
     * 不做手势（长按/左划在小米上都出过问题），按钮永远可见，点了就有效。
     */
    private fun tagRow(name: String, selected: Boolean): android.view.View {
        val activity = context
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val nameView = TextView(activity).apply {
            text = name
            textSize = 15.5f
            setTextColor(ContextCompat.getColor(activity,
                if (selected) R.color.accent else R.color.app_text))
            val p = (14 * d).toInt()
            setPadding(p, p, 0, p)
            background = rippleBg(activity)
            setOnClickListener {
                onPick(name)
                dismiss()
            }
        }
        row.addView(nameView, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        ))

        row.addView(actionText(activity.getString(R.string.tag_rename_short), R.color.app_text_sub) {
            dismiss(); onRename(name)
        })
        row.addView(actionText(activity.getString(R.string.tag_delete_short), R.color.danger) {
            dismiss(); onDelete(name)
        })
        return row
    }

    /** 行尾的小字操作按钮：纯文字 + 涟漪，颜色区分（改 = 灰、删 = 红） */
    private fun actionText(label: String, fgRes: Int, onClick: () -> Unit): TextView {
        val activity = context
        return TextView(activity).apply {
            text = label
            textSize = 13.5f
            gravity = Gravity.CENTER
            val p = (10 * d).toInt()
            setPadding(p, p, p, p)
            setTextColor(ContextCompat.getColor(activity, fgRes))
            background = rippleBg(activity)
            setOnClickListener { onClick() }
        }
    }
}

/**
 * 滚轮弹窗：一列可滚动的值 + 确定 / 取消。催促 / 复查时机、间隔数量共用。
 *
 * 为什么不用系统 NumberPicker：选中项上下那两条分隔线由它的私有字段控制，
 * 小米（HyperOS）上反射清不掉，黑线两轮都没去掉。这里干脆自己画：
 * RecyclerView + PagerSnapHelper 吸附，中间用一圈浅色圆角带表示选中，没有线，
 * 文字、颜色、行高全部跟主题走。
 */
class WheelSheet(
    activity: Activity,
    title: String,
    /** 滚轮每一格显示的文案 */
    private val values: List<String>,
    /** 初始停在第几格 */
    initialIndex: Int,
    private val onOk: (Int) -> Unit,
) : BottomSheetDialog(activity) {

    private val d = activity.resources.displayMetrics.density

    /** 当前停中间的那一格 */
    private var picked = initialIndex.coerceIn(0, (values.size - 1).coerceAtLeast(0))

    /** 一行的高度：够手指点，也够放下 19sp 的字 */
    private val rowHeight = (44 * d).toInt()

    private val wheel: RecyclerView

    private inner class WheelAdapter(private val ctx: Context) : RecyclerView.Adapter<WheelAdapter.VH>() {

        /** 选中项换人时只重绘新旧两格（比 notifyDataSetChanged 省得多） */
        fun moveSelection(to: Int) {
            val from = picked
            picked = to
            notifyItemChanged(from)
            notifyItemChanged(to)
        }

        inner class VH(val text: TextView) : RecyclerView.ViewHolder(text)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val tv = TextView(ctx).apply {
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT, rowHeight
                )
                gravity = Gravity.CENTER
                typeface =
                    androidx.core.content.res.ResourcesCompat.getFont(ctx, R.font.zhuque_fangsong)
                isClickable = true
            }
            return VH(tv)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val selected = position == picked
            holder.text.text = values[position]
            holder.text.setTextColor(
                ContextCompat.getColor(ctx, if (selected) R.color.accent else R.color.app_text_sub)
            )
            holder.text.textSize = if (selected) 19f else 15f
            holder.text.setOnClickListener { wheel.scrollToPosition(position) }
        }

        override fun getItemCount(): Int = values.size
    }

    private val adapter = WheelAdapter(activity)

    init {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * d).toInt(), (4 * d).toInt(), (20 * d).toInt(), (12 * d).toInt())
        }
        root.addView(sheetTitle(activity, title),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (4 * d).toInt() })

        wheel = RecyclerView(activity).apply {
            layoutManager = LinearLayoutManager(activity)
            adapter = this@WheelSheet.adapter
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            // 上下各留两行空白，首尾的值也能滚到正中间
            setPadding(0, rowHeight * 2, 0, rowHeight * 2)
            clipToPadding = false
            // 滚轮上的纵向手势归滚轮，不让抽屉趁机关掉
            setOnTouchListener { v, _ ->
                v.parent?.requestDisallowInterceptTouchEvent(true)
                false
            }
        }
        PagerSnapHelper().attachToRecyclerView(wheel)

        // 滚到哪一格就选中哪一格；顺手给一记轻震，像真的滚轮
        wheel.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val center = rv.height / 2
                var best = -1
                var bestDist = Int.MAX_VALUE
                for (i in 0 until rv.childCount) {
                    val child = rv.getChildAt(i) ?: continue
                    val dist = kotlin.math.abs((child.top + child.bottom) / 2 - center)
                    if (dist < bestDist) {
                        bestDist = dist
                        best = rv.getChildAdapterPosition(child)
                    }
                }
                if (best >= 0 && best != picked) {
                    adapter.moveSelection(best)
                    rv.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
            }
        })

        // 中间那圈浅色圆角带 = 选中标记（替代系统滚轮的黑色分隔线）
        val band = View(activity).apply {
            background = GradientDrawable().apply {
                cornerRadius = 10 * d
                setColor(ContextCompat.getColor(activity, R.color.accent_container))
            }
        }
        val frame = FrameLayout(activity).apply {
            addView(band, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, rowHeight, Gravity.CENTER_VERTICAL
            ))
            addView(wheel, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, rowHeight * 5
            ))
        }
        root.addView(frame, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            rowHeight * 5
        ).apply { topMargin = (8 * d).toInt() })

        val btnCancel = MaterialButton(
            ContextThemeWrapper(activity, R.style.ThemeOverlay_Taktu_SheetButtonText)
        ).apply {
            text = activity.getString(R.string.sheet_action_cancel)
            setTextColor(ContextCompat.getColor(activity, R.color.app_text_sub))
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
        ).apply { topMargin = (10 * d).toInt() })

        btnCancel.setOnClickListener { dismiss() }
        btnOk.setOnClickListener {
            onOk(picked)
            dismiss()
        }

        val scroll = ScrollView(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            addView(root)
        }
        setContentView(Sheets.frost(activity, scroll))
        ButtonTuner.tune(scroll)
        setOnShowListener { Sheets.forceExpanded(this) }
        // 等布局量完再定位，否则 scrollToPosition 会被 padding 吃掉
        wheel.post { wheel.scrollToPosition(picked) }
    }
}

/** 带输入框的小弹窗（新建 / 重命名标签共用） */
class TextSheet(
    activity: Activity,
    title: String,
    preset: String,
    private val onOk: (String) -> Unit,
) : BottomSheetDialog(activity) {

    private val d = activity.resources.displayMetrics.density

    init {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * d).toInt(), (4 * d).toInt(), (20 * d).toInt(), (12 * d).toInt())
        }
        root.addView(sheetTitle(activity, title),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (4 * d).toInt() })

        val input = EditText(activity).apply {
            hint = activity.getString(R.string.tag_custom_hint)
            setText(preset)
            setSingleLine()
            if (preset.isNotEmpty()) setSelection(preset.length)
            background = ContextCompat.getDrawable(activity, R.drawable.bg_input)
            textSize = 15f
            setTextColor(ContextCompat.getColor(activity, R.color.app_text))
            setHintTextColor(ContextCompat.getColor(activity, R.color.app_text_sub))
            val p = (16 * d).toInt()
            setPadding(p, 0, p, 0)
        }
        root.addView(input, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, (54 * d).toInt()
        ).apply { topMargin = (14 * d).toInt() })

        val btnCancel = MaterialButton(
            ContextThemeWrapper(activity, R.style.ThemeOverlay_Taktu_SheetButtonText)
        ).apply {
            text = activity.getString(R.string.sheet_action_cancel)
            setTextColor(ContextCompat.getColor(activity, R.color.app_text_sub))
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
        ).apply { topMargin = (10 * d).toInt() })

        btnCancel.setOnClickListener { dismiss() }
        btnOk.setOnClickListener {
            onOk(input.text.toString().trim())
            dismiss()
        }

        val scroll = ScrollView(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            addView(root)
        }
        setContentView(Sheets.frost(activity, scroll))
        ButtonTuner.tune(scroll)
        setOnShowListener { Sheets.forceExpanded(this) }
    }
}

/* ---------------- 共用小零件 ---------------- */

/** 抽屉标题：居中 19sp，与 DateSheet 一致 */
private fun sheetTitle(activity: Activity, text: String): TextView =
    TextView(activity).apply {
        this.text = text
        textSize = 19f
        gravity = Gravity.CENTER
        setTextColor(ContextCompat.getColor(activity, R.color.app_text))
    }

/** 一行可点的列表项：选中项用陶土色，带系统涟漪反馈 */
private fun addRow(
    parent: LinearLayout,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val activity = parent.context as Activity
    val d = activity.resources.displayMetrics.density
    val row = TextView(activity).apply {
        text = label
        textSize = 15.5f
        setTextColor(ContextCompat.getColor(activity,
            if (selected) R.color.accent else R.color.app_text))
        val p = (14 * d).toInt()
        setPadding(p, p, p, p)
        background = rippleBg(activity)
        setOnClickListener { onClick() }
    }
    parent.addView(row, LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ))
}

/** 系统 Material 涟漪，套在纯 TextView 上给点按反馈 */
private fun rippleBg(context: android.content.Context): Drawable? {
    val a = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
    val drawable = a.getDrawable(0)
    a.recycle()
    return drawable
}
