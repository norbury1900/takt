package com.norbury.taktu

import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.norbury.taktu.alarm.AlarmReceiver
import com.norbury.taktu.alarm.AlarmScheduler
import com.norbury.taktu.alarm.RingService
import com.norbury.taktu.data.AppDatabase
import com.norbury.taktu.data.CheckIn
import com.norbury.taktu.data.Task
import com.norbury.taktu.data.TaskRecord
import com.norbury.taktu.util.MiLinkBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 任务的「打卡界面」：两个入口进来 ——
 *   1. 响铃页点某条任务（fromRing = true：催促阶段一动就静音，做完停铃）
 *   2. 主界面任务卡上的「打卡」按钮（fromRing = false：跟铃声无关）
 *
 * 清单项的两种形态：
 *   - 多组项（`哑铃卧推|4|8`）→ 「− / 2/4 / +」，点 + 记一组，满组自动算完成
 *   - 一次性项（`热身`）→ 一颗勾（和任务卡上那颗完成按钮同款）
 * 全部项完成后本页自动关闭，回响铃页 / 主界面会各自刷新。
 */
class RingTaskActivity : BaseActivity() {

    private var taskId = -1L
    private var type = AlarmReceiver.TYPE_PRE
    private var fromRing = true
    private var task: Task? = null
    private var record: TaskRecord? = null

    companion object {
        const val EXTRA_TASK_ID = "ring_task_id"
        const val EXTRA_TYPE = "ring_task_type"
        const val EXTRA_FROM_RING = "ring_task_from_ring"

        /** 多时刻任务：本次响的是当天第几个时刻（响铃页带进来，确认时写进记录） */
        const val EXTRA_TIME_IDX = "ring_task_time_idx"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(R.layout.activity_ring_task)

        taskId = intent.getLongExtra(EXTRA_TASK_ID, -1L)
        type = intent.getIntExtra(EXTRA_TYPE, AlarmReceiver.TYPE_PRE)
        fromRing = intent.getBooleanExtra(EXTRA_FROM_RING, true)
        if (taskId <= 0) {
            finish()
            return
        }

        findViewById<ImageButton>(R.id.btnRingTaskBack).setOnClickListener { finish() }
        findViewById<MaterialButton>(R.id.btnRingTaskMinus).setOnClickListener { changeTaskSet(-1) }
        findViewById<MaterialButton>(R.id.btnRingTaskPlus).setOnClickListener { changeTaskSet(+1) }
        findViewById<MaterialButton>(R.id.btnRingTaskDone).setOnClickListener { completeAndFinish() }

        load()
    }

    private fun load() {
        CoroutineScope(Dispatchers.IO).launch {
            val dao = AppDatabase.get(this@RingTaskActivity).taskDao()
            val t = dao.getByIdSync(taskId)
            val rec = t?.let { dao.getRecordSync(it.recordKeyOn(AppDatabase.todayKey()), it.id) }
            withContext(Dispatchers.Main) {
                if (t == null) {
                    finish()
                    return@withContext
                }
                task = t
                record = rec
                render()
            }
        }
    }

    /** 某清单项已完成几组（老数据只写了 checkedItems，按"满组"折算） */
    private fun doneOf(index: Int, spec: Task.Item): Int {
        val p = record?.itemProgressMap?.get(index) ?: 0
        if (p > 0) return p
        return if (record?.checkedItemIndexes?.contains(index) == true) spec.sets else 0
    }

    private fun render() {
        val t = task ?: return
        val todayKey = AppDatabase.todayKey()
        // 没有当天记录就是 0：Task.doneCount 跨天可能是昨天的满额（v3.34.112）
        val done = record?.doneCount ?: 0

        findViewById<TextView>(R.id.tvRingTaskTitle).text = t.name
        findViewById<TextView>(R.id.tvRingTaskMeta).text = buildString {
            if (t.isMultiTime) {
                // 多时刻：逐个列出各时刻自己的开始 / 复查（v3.34.21 逐时刻偏移）
                val times = t.timesOn(todayKey)
                append(times.size).append(" 个时刻")
                append(" · 已确认 ")
                append(times.indices.count { t.timeConfirmed(record, it) })
                append('/').append(times.size)
            } else {
                append(Task.formatMin(t.deadlineOn(todayKey))).append(" 目标")
                if (t.preOffsetMin >= 0) {
                    append(" · 开始 ").append(Task.formatMin(t.preAlertOn(todayKey)))
                }
                if (t.postOffsetMin >= 0) {
                    append(" · 截止 ").append(Task.formatMin(t.finalCheckOn(todayKey)))
                }
                if (record?.completed == true) append(" · 已完成")
            }
        }

        // ── 任务级组数（整条任务有目标次数时才显示）──
        val setsCard = findViewById<View>(R.id.cardRingSets)
        if (t.targetCount > 0) {
            setsCard.visibility = View.VISIBLE
            val step = stepOf(t)
            val target = t.targetCount.coerceAtLeast(1)
            val setsDone = if (step > 0) done / step else 0
            val setsTotal = if (step > 0) target / step else 0
            findViewById<TextView>(R.id.tvRingTaskSetInfo).text =
                "第 $setsDone/$setsTotal 组 · $done/$target（${done * 100 / target}%）"
        } else {
            setsCard.visibility = View.GONE
        }

        // ── 清单：多组项给 +/−，一次性项给「完成」按钮 ──
        val specs = t.itemSpecs
        val itemsCard = findViewById<View>(R.id.cardRingItems)
        if (specs.isEmpty()) {
            // 没有清单时，多时刻任务用这个区列「各个时刻」，勾选入口照样有
            if (t.isMultiTime) {
                showTimeRows()
            } else {
                itemsCard.visibility = View.GONE
            }
            return
        }
        itemsCard.visibility = View.VISIBLE
        val finishedCount = specs.indices.count { doneOf(it, specs[it]) >= specs[it].sets }
        findViewById<TextView>(R.id.tvRingTaskItemInfo).text = "清单 $finishedCount/${specs.size}"

        val box = findViewById<LinearLayout>(R.id.boxRingTaskItems)
        box.removeAllViews()
        val d = resources.displayMetrics.density
        val dim = ContextCompat.getColor(this, R.color.app_text_sub)
        val ripple = obtainStyledAttributes(
            intArrayOf(android.R.attr.selectableItemBackground)
        ).let { a -> val dr = a.getDrawable(0); a.recycle(); dr }

        specs.forEachIndexed { i, spec ->
            val itemDone = doneOf(i, spec)
            val finished = itemDone >= spec.sets
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, (3 * d).toInt(), 0, (3 * d).toInt())
            }

            // 名字：多组项带上「4×8」提示；一次性项点名字也算完成
            val nameTv = TextView(this).apply {
                val suffix = when {
                    spec.sets <= 1 -> ""
                    spec.reps > 0 -> "  ${spec.sets}×${spec.reps}"
                    else -> "  ${spec.sets} 组"
                }
                // 方框（v3.34.83）：□ 未勾 / ■ 已勾，与任务卡一致；左侧不缩进
                text = (if (finished) "■ " else "□ ") + spec.name + suffix
                textSize = 14.5f
                setTextColor(
                    if (finished) dim
                    else ContextCompat.getColor(this@RingTaskActivity, R.color.app_text)
                )
                if (finished) {
                    paintFlags = paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
                }
                val p = (8 * d).toInt()
                setPadding(0, p, p, p)
                background = ripple
                if (spec.sets <= 1) {
                    setOnClickListener { changeItemSet(i, spec, if (finished) -1 else +1) }
                }
            }
            row.addView(
                nameTv,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )

            if (spec.sets <= 1) {
                // 一次性项：一颗勾（未完成 = 陶土实心，已完成 = 白底灰勾，与任务卡一致）
                row.addView(iconButton(R.drawable.ic_check, !finished) {
                    changeItemSet(i, spec, if (finished) -1 else +1)
                })
            } else {
                // 多组项：− n/N +
                row.addView(counterButton("−", false) { changeItemSet(i, spec, -1) })
                row.addView(TextView(this).apply {
                    text = "$itemDone/${spec.sets}"
                    textSize = 13.5f
                    gravity = android.view.Gravity.CENTER
                    setTextColor(ContextCompat.getColor(this@RingTaskActivity, R.color.app_text_sub))
                    layoutParams = LinearLayout.LayoutParams((50 * d).toInt(), (32 * d).toInt())
                })
                row.addView(counterButton("+", true) { changeItemSet(i, spec, +1) })
            }
            box.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }

        // 多时刻任务：清单下面再列各个时刻（各自的目标 / 始 / 止 + 自己的勾）
        if (t.isMultiTime) {
            addTimeRows(box, t, AppDatabase.todayKey())
        }
    }

    /**
     * 往清单容器里追加逐时刻行（已确认的打勾划掉）。
     * 列出**全部**时刻（含已确认的），让用户看清整体进度；无清单的多时刻任务
     * 整个清单区都由它接管（[showTimeRows]）。
     */
    private fun addTimeRows(box: LinearLayout, t: Task, todayKey: String) {
        val d = resources.displayMetrics.density
        val times = t.timesOn(todayKey)
        val ripple = obtainStyledAttributes(
            intArrayOf(android.R.attr.selectableItemBackground)
        ).let { a -> val dr = a.getDrawable(0); a.recycle(); dr }
        val doneColor = ContextCompat.getColor(this, R.color.ok_on_container)
        val textColor = ContextCompat.getColor(this, R.color.app_text)

        val header = TextView(this).apply {
            text = getString(R.string.ring_times_header)
            textSize = 11.5f
            setTextColor(ContextCompat.getColor(this@RingTaskActivity, R.color.app_text_sub))
            val p = (4 * d).toInt()
            setPadding(p, (8 * d).toInt(), p, (2 * d).toInt())
        }
        box.addView(
            header,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        times.forEachIndexed { idx, timeMin ->
            val ok = t.timeConfirmed(record, idx)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                background = ripple
                val p = (10 * d).toInt()
                setPadding(p, p, p, p)
                isClickable = true
                setOnClickListener {
                    if (ok) cancelTimeAndReload(idx) else confirmTimeAndReload(idx)
                }
            }
            row.addView(
                TextView(this).apply {
                    text = buildString {
                        append(if (ok) "✓ " else "○ ")
                        append(Task.formatMin(timeMin))
                        if (t.preOffsetOf(timeMin) >= 0) {
                            append(" · 始 ").append(Task.formatMin(t.preAlertOfTime(timeMin)))
                        }
                        if (t.postOffsetOf(timeMin) >= 0) {
                            append(" · 止 ").append(Task.formatMin(t.finalCheckOfTime(timeMin)))
                        }
                    }
                    textSize = 14.5f
                    setTextColor(if (ok) doneColor else textColor)
                    if (ok) paintFlags = paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            row.addView(iconButton(R.drawable.ic_check, !ok) {
                if (ok) cancelTimeAndReload(idx) else confirmTimeAndReload(idx)
            })
            box.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
    }

    /** 取消确认某一个时刻（反悔） */
    private fun cancelTimeAndReload(index: Int) {
        tickHaptic()
        val t = task ?: return
        CoroutineScope(Dispatchers.IO).launch {
            val dao = AppDatabase.get(this@RingTaskActivity).taskDao()
            val total = t.timesOn(AppDatabase.todayKey()).size
            CheckIn.update(this@RingTaskActivity, dao, t) { it.unconfirmTime(index, total) }
            withContext(Dispatchers.Main) { load() }
        }
    }

    /**
     * 清单行里的图标按钮：和 counterButton 同尺寸（40×32），只是画图标不写字。
     * 用 ImageButton —— MaterialButton 的图标居中不可靠（分量不同步，会偏）。
     */
    private fun iconButton(@DrawableRes res: Int, filled: Boolean, onClick: () -> Unit): ImageButton =
        ImageButton(this).apply {
            val d = resources.displayMetrics.density
            setImageResource(res)
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = ContextCompat.getDrawable(
                this@RingTaskActivity, if (filled) R.drawable.bg_pill_accent else R.drawable.bg_pill
            )
            imageTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(
                    this@RingTaskActivity,
                    if (filled) R.color.accent_on else R.color.app_text_sub
                )
            )
            contentDescription = getString(
                if (filled) R.string.mark_done else R.string.undo_done
            )
            val p = (9 * d).toInt()
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams((40 * d).toInt(), (32 * d).toInt())
            setOnClickListener { onClick() }
        }

    /** 清单行里的紧凑小按钮（32dp 高，无最小宽度，圆角 8dp） */
    private fun counterButton(label: String, filled: Boolean, onClick: () -> Unit): MaterialButton =
        MaterialButton(this).apply {
            text = label
            textSize = if (label.length > 1) 12.5f else 15f
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            minimumHeight = 0
            insetTop = 0
            insetBottom = 0
            setPadding(
                (if (label.length > 1) 14 else 0) * resources.displayMetrics.density.toInt(),
                0,
                (if (label.length > 1) 14 else 0) * resources.displayMetrics.density.toInt(),
                0
            )
            if (filled) {
                setTextColor(ContextCompat.getColor(context, R.color.accent_on))
                backgroundTintList = android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(context, R.color.accent)
                )
            } else {
                setTextColor(ContextCompat.getColor(context, R.color.app_text))
                backgroundTintList = android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(context, R.color.app_card_alt)
                )
            }
            val d = resources.displayMetrics.density
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, (32 * d).toInt()
            // 单字符按钮（− / +）定宽，否则「−」只剩字形宽，看着被挤扁
            ).apply { if (label.length == 1) width = (40 * d).toInt() }
            setOnClickListener { onClick() }
        }

    /** 记一组 / 退一组（任务级 targetCount） */
    private fun changeTaskSet(delta: Int) {
        tickHaptic()
        val t = task ?: return
        CoroutineScope(Dispatchers.IO).launch {
            val dao = AppDatabase.get(this@RingTaskActivity).taskDao()
            val fresh = dao.getByIdSync(t.id) ?: return@launch
            val step = stepOf(fresh)
            val target = fresh.targetCount.coerceAtLeast(1)
            // v3.34.21：读旧记录再改字段（保住 t 条目与清单勾选），完成判定按逐时刻分路
            // 基准取当天记录：Task.doneCount 跨天可能是昨天的满额（v3.34.112）
            var full = false
            val committed = CheckIn.update(this@RingTaskActivity, dao, fresh) { base ->
                val done = (base.doneCount + delta * step).coerceIn(0, target)
                full = done >= target
                base.copy(
                    doneCount = done,
                    completed = if (fresh.isMultiTime) base.completed else full
                )
            }
            val completed = committed.completed
            withContext(Dispatchers.Main) {
                silenceIfUrging()
                if (completed || full) finish() else load()
            }
        }
    }

    /** 清单里第 index 项 记一组 / 退一组（一次性项就是 完成 / 取消） */
    private fun changeItemSet(index: Int, spec: Task.Item, delta: Int) {
        tickHaptic()
        val t = task ?: return
        CoroutineScope(Dispatchers.IO).launch {
            val dao = AppDatabase.get(this@RingTaskActivity).taskDao()

            // 先把当前状态折成进度表（兼容只有 checkedItems 的老记录）
            val specs = t.itemSpecs
            var itemsDone = false
            val allDone = CheckIn.update(this@RingTaskActivity, dao, t) { rec ->
                val progress = HashMap<Int, Int>()
                specs.indices.forEach { i ->
                    val raw = rec.itemProgressMap[i] ?: 0
                    progress[i] = when {
                        raw > 0 -> raw
                        rec.checkedItemIndexes.contains(i) -> specs[i].sets
                        else -> 0
                    }
                }
                progress[index] = ((progress[index] ?: 0) + delta).coerceIn(0, spec.sets)

                val fullIdx = specs.indices.filter { (progress[it] ?: 0) >= specs[it].sets }
                itemsDone = fullIdx.size == specs.size

                /*
                 * v3.34.21：保留已确认的时刻条目（mergeCheckedItems）——
                 * 以前这里直接 checkedItems = fullIdx.joinToString(",")，
                 * 把多时刻任务已经确认的 "t下标" 一起冲掉了。
                 * 完成判定也随之分路：多时刻任务只看「全部时刻都确认没」，清单勾完不算。
                 */
                rec.mergeCheckedItems(fullIdx).copy(
                    completed = if (t.isMultiTime) rec.completed else itemsDone,
                    doneCount = if (itemsDone && t.targetCount > 0) t.targetCount else rec.doneCount,
                    itemProgress = progress.filter { it.value > 0 }
                        .toSortedMap().entries.joinToString(",") { "${it.key}:${it.value}" }
                )
            }.completed
            withContext(Dispatchers.Main) {
                silenceIfUrging()
                if (allDone || itemsDone) finish() else load()
            }
        }
    }

    private fun completeAndFinish() {
        tickHaptic()
        val t = task ?: return
        /*
         * v3.34.21：多时刻任务逐时刻确认，**只有全部时刻都确认了才关页面**。
         * 以前确认一个时刻就直接 finish()，用户看到的是「任务已完成」——
         * 但另一个时刻还没到、还没响，不该算做完（用户 2026-10-22 明确抱怨）。
         * 只剩一个时刻没确认时，页面上直接把它列出来让用户点。
         */
        if (t.isMultiTime) {
            val left = t.timesOn(AppDatabase.todayKey()).indices
                .filter { !t.timeConfirmed(record, it) }
            if (left.size > 1) {
                showTimeRows()
                return
            }
        }
        CoroutineScope(Dispatchers.IO).launch {
            val dao = AppDatabase.get(this@RingTaskActivity).taskDao()
            // 多时刻任务：响铃页带时刻下标进来 → 只确认这一个时刻
            val timeIdx = if (t.isMultiTime) {
                intent.getIntExtra(EXTRA_TIME_IDX, -1).takeIf { it >= 0 }
                    ?: t.earliestUnconfirmed(record)
            } else -1
            if (timeIdx >= 0) {
                val total = t.timesOn(AppDatabase.todayKey()).size
                val updated = CheckIn.update(this@RingTaskActivity, dao, t) {
                    it.confirmTime(timeIdx, total)
                }
                withContext(Dispatchers.Main) {
                    if (fromRing) RingService.stop(this@RingTaskActivity)
                    val left = total - updated.confirmedTimes.size
                    if (updated.completed) {
                        toast("「${t.name}」今天全部时刻都完成了")
                        finish()
                    } else {
                        // 只确认了一个时刻：留在页面继续，meta 上还剩几个没做
                        toast("已确认，还剩 $left 个时刻")
                        load()
                    }
                }
            } else {
                val done = if (t.targetCount > 0) t.targetCount else 0
                CheckIn.update(this@RingTaskActivity, dao, t) {
                    it.copy(completed = true, doneCount = done)
                }
                withContext(Dispatchers.Main) {
                    if (fromRing) RingService.stop(this@RingTaskActivity)
                    finish()
                }
            }
        }
    }

    /**
     * 无清单的多时刻任务：清单区改列**全部**时刻（含已确认状态，可勾 / 可反悔）。
     * 以前这里是 renderTimesTodo：写死渲染「○ 时间」，从不读确认状态，
     * 勾完 reload 后行原样不动 —— 用户 2026-10-23 报告「勾了没反馈但效果确实有」。
     * 现在统一走 [addTimeRows]（✓/○ + 删除线 + 状态随 record 更新）。
     */
    private fun showTimeRows() {
        val t = task ?: return
        val todayKey = AppDatabase.todayKey()
        findViewById<View>(R.id.cardRingItems).visibility = View.VISIBLE
        val box = findViewById<LinearLayout>(R.id.boxRingTaskItems)
        box.removeAllViews()
        val left = t.timesOn(todayKey).indices.count { !t.timeConfirmed(record, it) }
        findViewById<TextView>(R.id.tvRingTaskItemInfo).text =
            getString(R.string.ring_times_todo_fmt, left)
        addTimeRows(box, t, todayKey)
    }

    private fun confirmTimeAndReload(index: Int) {
        tickHaptic()
        val t = task ?: return
        CoroutineScope(Dispatchers.IO).launch {
            val dao = AppDatabase.get(this@RingTaskActivity).taskDao()
            val total = t.timesOn(AppDatabase.todayKey()).size
            val updated = CheckIn.update(this@RingTaskActivity, dao, t) {
                it.confirmTime(index, total)
            }
            withContext(Dispatchers.Main) {
                if (fromRing) RingService.stop(this@RingTaskActivity)
                if (updated.completed) {
                    toast("「${t.name}」今天全部时刻都完成了")
                    finish()
                } else load()
            }
        }
    }

    /** 响铃进来且还在催促阶段：一动就先静音（开始做了，铃别吵）；从卡片进来的不碰铃声 */
    private fun silenceIfUrging() {
        if (fromRing && type == AlarmReceiver.TYPE_PRE) RingService.stop(this)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
