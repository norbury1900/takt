package com.norbury.taktu.sheet

import android.app.Activity
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.norbury.taktu.R
import com.norbury.taktu.data.AppDatabase
import com.norbury.taktu.data.QuietWindow
import com.norbury.taktu.data.QuietWindows
import com.norbury.taktu.util.ButtonTuner
import com.norbury.taktu.util.LunarCalendar
import com.norbury.taktu.util.Prefs
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 免打扰窗口管理弹窗（v3.34.16，用户 2026-10-22 软考场景）。
 *
 * 落在窗口内的提醒整体后移到窗口结束时刻：软考当天 08:30~12:30、14:30~16:30，
 * 原本排在考试时段里的提醒改到 12:30 / 16:30 响，不打扰考试。
 *
 * 交互：列出全部窗口（按日期）→ 每条可删；「＋ 添加窗口」走
 * DateSheet（日期）→ TimeSheet（开始）→ TimeSheet（结束）三步。
 * 任何变更都会写 Prefs 并回调 [onChanged]（设置页据此重排闹钟）。
 */
class QuietSheet(
    private val activity: Activity,
    private val onChanged: () -> Unit
) : BottomSheetDialog(activity) {

    private val d = activity.resources.displayMetrics.density

    /** 内存中的窗口表（增删都落这里，保存时统一序列化进 Prefs） */
    private var windows: List<QuietWindow> =
        QuietWindows.prunePast(QuietWindows.parse(Prefs.quietWindows(activity)), AppDatabase.todayKey())

    private lateinit var listBox: LinearLayout
    private lateinit var tvEmpty: TextView

    init {
        buildContent()
    }

    /* ---------------- 界面 ---------------- */

    private fun buildContent() {
        val textSub = ContextCompat.getColor(activity, R.color.app_text_sub)

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding((20 * d).toInt(), (4 * d).toInt(), (20 * d).toInt(), (12 * d).toInt())
        }

        root.addView(TextView(activity).apply {
            text = activity.getString(R.string.quiet_title)
            textSize = 19f
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(activity, R.color.app_text))
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = (10 * d).toInt() })

        root.addView(TextView(activity).apply {
            text = activity.getString(R.string.quiet_desc)
            textSize = 12.5f
            // setLineSpacing(extra, mult)：TextView 的 multiplier 只有 getter，没有单独 setter
            setLineSpacing(0f, 1.5f)
            setTextColor(textSub)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = (14 * d).toInt() })

        // 窗口列表
        listBox = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listBox, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        tvEmpty = TextView(activity).apply {
            text = activity.getString(R.string.quiet_empty)
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(textSub)
            setPadding(0, (18 * d).toInt(), 0, (18 * d).toInt())
        }
        root.addView(tvEmpty, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // ＋ 添加窗口（描边钮，程序化构造套主题覆盖，与 XML 里同款）
        val btnAdd = MaterialButton(
            ContextThemeWrapper(activity, R.style.ThemeOverlay_Taktu_SheetButtonOutlined)
        ).apply {
            text = activity.getString(R.string.quiet_add)
            setOnClickListener { startAddFlow() }
        }
        root.addView(btnAdd, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (12 * d).toInt() })

        // 完成（实心钮）
        val btnDone = MaterialButton(activity).apply {
            text = activity.getString(R.string.sheet_action_ok)
            setOnClickListener { dismiss() }
        }
        root.addView(btnDone, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (10 * d).toInt() })

        renderList()

        val scroll = ScrollView(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            addView(root)
        }
        setContentView(scroll)
        ButtonTuner.tune(root)

        setOnShowListener { dialogInterface ->
            Sheets.forceExpanded(dialogInterface as BottomSheetDialog)
        }
    }

    /** 重建列表：每条窗口一行（日期 + 时段 + 删除），行间 1dp 分隔线 */
    private fun renderList() {
        listBox.removeAllViews()
        val sorted = windows.sortedWith(compareBy({ it.dateKey }, { it.startMin }))
        tvEmpty.visibility = if (sorted.isEmpty()) View.VISIBLE else View.GONE

        sorted.forEach { w ->
            listBox.addView(buildRow(w))
            listBox.addView(View(activity).apply {
                setBackgroundColor(ContextCompat.getColor(activity, R.color.app_stroke))
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (1 * d).toInt()
            ))
        }
    }

    private fun buildRow(w: QuietWindow): View {
        val textSub = ContextCompat.getColor(activity, R.color.app_text_sub)
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (10 * d).toInt(), 0, (10 * d).toInt())

            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(activity).apply {
                    text = fmtDate(w.dateKey)
                    textSize = 14.5f
                    setTextColor(ContextCompat.getColor(activity, R.color.app_text))
                })
                addView(TextView(activity).apply {
                    text = activity.getString(
                        R.string.timeline_range_fmt,
                        QuietWindows.fmtMin(w.startMin),
                        QuietWindows.fmtMin(w.endMin)
                    )
                    textSize = 13f
                    setTextColor(textSub)
                })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            addView(ImageButton(activity).apply {
                setImageResource(R.drawable.ic_delete)
                background = ContextCompat.getDrawable(activity, R.drawable.bg_circle_button)
                val p = (9 * d).toInt()
                setPadding(p, p, p, p)
                imageTintList = android.content.res.ColorStateList.valueOf(textSub)
                contentDescription = activity.getString(R.string.quiet_delete)
                setOnClickListener {
                    windows = windows - w
                    save()
                }
            }, LinearLayout.LayoutParams(
                (36 * d).toInt(), (36 * d).toInt()
            ))
        }
    }

    /* ---------------- 添加流程：日期 → 开始 → 结束 ---------------- */

    private fun startAddFlow() {
        DateSheet(activity, activity.getString(R.string.quiet_pick_date), DayPick.Solar(todayKey())) { pick ->
            val key = when (pick) {
                is DayPick.Solar -> pick.key
                is DayPick.Lunar -> {
                    val y = if (pick.year > 0) pick.year else Calendar.getInstance().get(Calendar.YEAR)
                    LunarCalendar.lunarToSolar(y, pick.month, pick.day)?.toString()
                }
                DayPick.Never -> null
            } ?: return@DateSheet

            TimeSheet(activity, activity.getString(R.string.quiet_pick_start), 8 * 60 + 30) { startMin ->
                TimeSheet(activity, activity.getString(R.string.quiet_pick_end), 12 * 60 + 30) { endMin ->
                    if (endMin <= startMin) {
                        Toast.makeText(activity, R.string.quiet_err_order, Toast.LENGTH_SHORT).show()
                        return@TimeSheet
                    }
                    windows = windows + QuietWindow(key, startMin, endMin)
                    save()
                }
            }
        }.show()
    }

    /** 保存并刷新；过期窗口顺手清掉（昨天的窗口留着只会碍眼） */
    private fun save() {
        windows = QuietWindows.prunePast(windows, AppDatabase.todayKey())
        Prefs.setQuietWindows(activity, QuietWindows.serialize(windows))
        renderList()
        onChanged()
    }

    /* ---------------- 工具 ---------------- */

    private fun todayKey(): String = AppDatabase.todayKey()

    /** "2026-10-24" → "10月24日 周六"；键格式异常时原样返回（防御，不崩弹窗） */
    private fun fmtDate(key: String): String = runCatching {
        SimpleDateFormat("M月d日 E", Locale.CHINA).format(
            SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).parse(key)!!
        )
    }.getOrNull() ?: key
}
