package com.norbury.taskalarm

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.norbury.taskalarm.alarm.AlarmReceiver
import com.norbury.taskalarm.alarm.AlarmScheduler
import com.norbury.taskalarm.alarm.RingService
import com.norbury.taskalarm.data.AppDatabase
import com.norbury.taskalarm.data.CheckIn
import com.norbury.taskalarm.data.RepeatRule
import com.norbury.taskalarm.data.Task
import com.norbury.taskalarm.data.TaskRecord
import com.norbury.taskalarm.data.TaskStatus
import com.norbury.taskalarm.util.MiLinkBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * 响铃全屏页。锁屏上直接弹出。
 *
 * 关键点：**到点后不是只显示触发的那一条，而是把「这个时间点还没做完」的全部列出来**。
 * 用户常常一次设好几条任务、截止时间又相近，一个个弹窗很烦；
 * 列成清单后可以逐条勾掉，勾完自动停铃。
 *
 * Activity 带 showWhenLocked + turnScreenOn，配合通知的 fullScreenIntent 生效。
 */
class AlarmRingActivity : BaseActivity() {

    private var taskId: Long = -1
    private var type: Int = AlarmReceiver.TYPE_PRE

    /** 多时刻任务：本次响的是当天第几个时刻（响铃确认要落到这一时刻上） */
    private var timeIdx: Int = 0

    /** 当前还没完成的全部任务（含触发的那条，它排在最前） */
    private var pending: List<Task> = emptyList()

    /** 正在响的任务 id；不依赖 intent 的 taskId（从通知进来曾经拿不到 id） */
    private var currentIds: Set<Long> = emptySet()

    /** 今天各任务的记录：主行上要显示组数 / 清单进度 */
    private var records: Map<Long, TaskRecord> = emptyMap()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 锁屏上显示并点亮屏幕
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

        setContentView(R.layout.activity_alarm_ring)

        taskId = intent.getLongExtra(RingService.EXTRA_TASK_ID, -1L)
        type = intent.getIntExtra(RingService.EXTRA_TYPE, AlarmReceiver.TYPE_PRE)
        timeIdx = intent.getIntExtra(RingService.EXTRA_TIME_IDX, 0)

        // 从通知点「稍后提醒」进来
        if (intent.action == RingService.ACTION_SNOOZE) {
            snooze()
            return
        }

        // 两种阶段按钮语义不同：
        //   催促阶段 = "我知道了"（只是去做的信号，不检查完成）
        //   复查阶段 = "完成"（只完成正在响的这一条，不扫全天 —— v3.34.110）
        val doneBtn = findViewById<com.google.android.material.button.MaterialButton>(R.id.btnDone)
        val snoozeBtn =
            findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSnooze)
        if (type == AlarmReceiver.TYPE_POST) {
            doneBtn.text = getString(R.string.mark_done)
            snoozeBtn.text = getString(R.string.ring_not_yet)
        } else {
            doneBtn.text = getString(R.string.ring_know)
            snoozeBtn.text = getString(R.string.ring_snooze)
        }

        doneBtn.setOnClickListener {
            if (type == AlarmReceiver.TYPE_POST) completeCurrent() else acknowledge()
        }
        snoozeBtn.setOnClickListener { snooze() }

        loadAll()
    }

    /** 从「任务界面」返回后刷新：那条做完了就该从清单里消失 */
    override fun onResume() {
        super.onResume()
        if (pending.isNotEmpty()) loadAll()
    }

    /** 同一时间点又有一条任务触发时（singleInstance 会复用本页），刷新清单 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val id = intent.getLongExtra(RingService.EXTRA_TASK_ID, -1L)
        if (id > 0) {
            taskId = id
            type = intent.getIntExtra(RingService.EXTRA_TYPE, AlarmReceiver.TYPE_PRE)
            timeIdx = intent.getIntExtra(RingService.EXTRA_TIME_IDX, 0)
        }
        // 页面已开着时点通知里的「稍后提醒」：以前只走 loadAll，按钮等于没用（v3.34.117）
        if (intent.action == RingService.ACTION_SNOOZE) {
            snooze()
            return
        }
        if (id > 0) loadAll()
    }

    /**
     * 查出「今天还没做完」的全部任务：启用中 + 今天该提醒（含法定节假日判定）+ 未完成。
     * 不要求它们各自也到了提醒点 —— 既然已经响了，就给一份今天待办的全貌。
     */
    private fun loadAll() {
        CoroutineScope(Dispatchers.IO).launch {
            val today = AppDatabase.todayKey()
            val dao = AppDatabase.get(this@AlarmRingActivity).taskDao()
            val recs = dao.getRecordsOfDate(today)
            val recMap = recs.associateBy { it.taskId }
            // 排序键用「当前该关注的时刻」（多时刻任务 = focusTime，随已确认时刻前移）：
            // 以前用 deadlineOn，它对多时刻任务恒等于首时刻 —— 12/19 的任务过了 12 点
            // 还按 12:00 排在最前（用户 2026-10-27 实测）
            val nowMin = Calendar.getInstance().let {
                it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE)
            }
            // 一律按**开始时间**（目标 - 提前量）排；只按目标时间排会把
            // 「23:00 目标、提前 80 分钟」的睡觉排到「22:00 目标」的解冻之后
            // （用户 2026-10-31 实测）。复查阶段对应的是目标 + 延后量。
            fun stageTime(t: Task): Int {
                val ft = TaskStatus.focusTime(
                    t, recMap[t.id]?.confirmedTimes ?: emptySet(), today, nowMin
                )
                return if (type == AlarmReceiver.TYPE_PRE) t.preAlertOfTime(ft)
                else ft + t.postOffsetOf(ft).coerceAtLeast(0)
            }
            val all = com.norbury.taskalarm.util.PendingTasks.now(this@AlarmRingActivity)
            val list = all.sortedBy { stageTime(it) }
            // 当前该高亮的：开始时间已到的最晚那一批；没有已到的就退回触发 id
            val started = all.filter { stageTime(it) <= nowMin }
            val latest = started.maxOfOrNull { stageTime(it) }
            currentIds = when {
                latest != null -> started.filter { stageTime(it) == latest }.map { it.id }.toSet()
                taskId > 0 -> setOf(taskId)
                else -> emptySet()
            }

            withContext(Dispatchers.Main) {
                pending = list
                records = recs.associateBy { it.taskId }
                // 全做完了就自动收工，不用再点一次
                if (list.isEmpty()) {
                    RingService.stop(this@AlarmRingActivity)
                    finish()
                    return@withContext
                }
                bind()
            }
        }
    }

    private fun bind() {
        val nameTv = findViewById<TextView>(R.id.tvName)
        val tagTv = findViewById<TextView>(R.id.tvTag)
        val descTv = findViewById<TextView>(R.id.tvDesc)

        tagTv.text = if (type == AlarmReceiver.TYPE_PRE) {
            getString(R.string.ring_tag_pre)
        } else {
            getString(R.string.ring_tag_post)
        }

        // 大字显示正在响的那条；拿不到就退回触发 id / 清单第一条
        val main = pending.firstOrNull { it.id in currentIds }
            ?: pending.firstOrNull { it.id == taskId }
            ?: pending.firstOrNull()
        if (main == null) {
            nameTv.text = getString(R.string.ring_all_done_title)
            descTv.text = getString(R.string.ring_all_done_desc)
        } else {
            nameTv.text = main.name
            descTv.text = describe(main)
        }

        renderList()
    }

    private fun describe(t: Task): String {
        val todayKey = AppDatabase.todayKey()
        val progress =
            if (t.targetCount > 0) "目标 ${t.targetCount} 次，当前 ${t.doneCount} 次。" else ""
        val cal = Calendar.getInstance()
        val nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        // 多时刻任务看焦点时刻（随已确认时刻前移），不是死盯首时刻
        val tm = TaskStatus.focusTime(
            t, records[t.id]?.confirmedTimes ?: emptySet(), todayKey, nowMin
        )
        val timing = if (type == AlarmReceiver.TYPE_PRE) {
            val left = tm - nowMin
            if (left > 0) "距离今天目标（${Task.formatMin(tm)}）还有 $left 分钟。"
            else "已到目标时间 ${Task.formatMin(tm)}。"
        } else {
            val over = nowMin - tm
            if (over > 0) "已超过截止时间 $over 分钟，今天还没完成。" else "截止时间快到了。"
        }
        return progress + timing
    }

    /**
     * 渲染未完成清单：每条一行（名 + 进度 + 展开箭头 + 圆形勾）。
     * 点行展开详情：组数（− / 记 1 组）与清单子项（逐个勾）——
     * 响铃这一刻往往正是开始做事的时候，所以能在这里直接打卡。
     */
    private fun renderList() {
        val box = findViewById<View>(R.id.boxOthers)
        val list = findViewById<LinearLayout>(R.id.listOthers)
        val title = findViewById<TextView>(R.id.tvOthersTitle)

        if (pending.isEmpty()) {
            box.visibility = View.GONE
            return
        }
        box.visibility = View.VISIBLE
        title.text = getString(R.string.ring_others_title, pending.size)

        list.removeAllViews()
        val inf = LayoutInflater.from(this)
        val accent = ContextCompat.getColor(this, R.color.accent)
        val neutral = ContextCompat.getColor(this, R.color.app_stroke)

        val todayKey = AppDatabase.todayKey()
        pending.forEach { t ->
            val v = inf.inflate(R.layout.item_ring_task, list, false)
            val rec = records[t.id]
            val checked = rec?.checkedItemIndexes ?: emptySet()
            val hasItems = t.itemList.isNotEmpty()
            val hasSets = t.targetCount > 0
            // 正在响的那条必须一眼可辨：加粗 + 左侧圆点变红。
            // v3.34.100：去掉底色框 —— 对齐后框边会压住完成按钮和圆点，很丑（用户实测）
            // v3.34.137：meta 的「当前 · 」前缀删了 —— 红点和加粗已经足够（用户 2026-11-10）
            val isCurrent = t.id in currentIds

            val nameTv = v.findViewById<TextView>(R.id.tvItemName)
            nameTv.text = t.name
            if (isCurrent) nameTv.setTypeface(nameTv.typeface, Typeface.BOLD)
            v.findViewById<TextView>(R.id.tvItemMeta).text = buildString {
                if (hasSets) append("${t.doneCount}/${t.targetCount}")
                if (hasItems) {
                    if (isNotEmpty()) append(" · ")
                    append("清单 ${checked.size}/${t.itemList.size}")
                }
                if (t.isMultiTime) {
                    // 多时刻：meta 只报进度计数，各时刻的 ✓/○ 竖排列在 boxTimes
                    // （原来全拼在 meta 一行，用户实测难读 + 勾选不刷新，v3.34.56）
                    val times = t.timesOn(todayKey)
                    val cf = rec?.confirmedTimes ?: emptySet()
                    val okCount = times.indices.count { it in cf }
                    if (isNotEmpty()) append(" · ")
                    append("时刻 ").append(okCount).append('/').append(times.size)
                } else {
                    if (isNotEmpty()) append(" · ")
                    append("${Task.formatMin(t.deadlineOn(todayKey))} 目标")
                }
            }
            // 多时刻：竖排时刻行（✓ = 已确认打勾划掉），一行一个时刻
            val timesBox = v.findViewById<LinearLayout>(R.id.boxTimes)
            if (t.isMultiTime) {
                val times = t.timesOn(todayKey)
                val cf = rec?.confirmedTimes ?: emptySet()
                timesBox.removeAllViews()
                val d = resources.displayMetrics.density
                times.forEachIndexed { i, tm ->
                    val ok = i in cf
                    timesBox.addView(
                        TextView(this).apply {
                            text = (if (ok) "✓ " else "○ ") + Task.formatMin(tm)
                            textSize = 12.5f
                            setTextColor(
                                ContextCompat.getColor(
                                    this@AlarmRingActivity,
                                    if (ok) R.color.ok_on_container else R.color.app_text_sub
                                )
                            )
                            if (ok) {
                                paintFlags = paintFlags or
                                    android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
                            }
                            // 首行不额外留白，后续行 2dp 行距（无卡片底，压紧一点）
                            if (i > 0) setPadding(0, (2 * d).toInt(), 0, 0)
                        },
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                    )
                }
                timesBox.visibility = View.VISIBLE
            } else {
                timesBox.visibility = View.GONE
            }
            v.findViewById<View>(R.id.dotState).background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (isCurrent) accent else neutral)
            }

            // 有子项 / 组数的任务显示箭头，点整行进入它的「任务界面」再打卡。
            // v3.34.137：监听从 rowRingMain 挪到整行根布局 —— 以前箭头不在点击区里，
            // 点它没反应，看着像「点不了」（用户 2026-11-10 实测）
            val chevron = v.findViewById<ImageView>(R.id.ivRingChevron)
            chevron.visibility = if (hasItems || hasSets) View.VISIBLE else View.INVISIBLE
            chevron.rotation = -90f   // 展开箭头转成「指向右侧」= 点进去
            v.setOnClickListener {
                startActivity(
                    Intent(this, RingTaskActivity::class.java)
                        .putExtra(RingTaskActivity.EXTRA_TASK_ID, t.id)
                        .putExtra(RingTaskActivity.EXTRA_TYPE, type)
                        // 多时刻任务：把本次触发的时刻下标带给打卡页，确认才落得对
                        .putExtra(
                            RingTaskActivity.EXTRA_TIME_IDX,
                            if (t.id == taskId) timeIdx else earliestUnconfirmedIdx(t)
                        )
                )
            }

            v.findViewById<ImageButton>(R.id.btnItemDone).setOnClickListener { markOne(t) }
            list.addView(v)
        }
    }

    /**
     * 单条标记完成。
     *
     * v3.34.21：多时刻任务确认一个时刻**不等于**这条做完了 ——
     * 只有全部时刻都确认才算，否则它留在清单里等下一个时刻。
     * v3.34.46：多时刻只确认**按当前时间匹配的那一个时刻**（[confirmOneTime]），
     * 不再顺延到下一个 —— 以前 complete 走「最早未确认」，行又毫无变化，
     * 用户以为没勾上再点一下，17:25 那次就被顺带确认掉了（下午不响的根因）。
     */
    private fun markOne(t: Task) {
        tickHaptic()
        CoroutineScope(Dispatchers.IO).launch {
            val rec = if (t.isMultiTime) confirmOneTime(t) else complete(t)
            withContext(Dispatchers.Main) {
                if (rec != null) {
                    records = records + (t.id to rec)
                    if (t.isMultiTime) {
                        val left = t.timesOn(AppDatabase.todayKey()).size - rec.confirmedTimes.size
                        toast(
                            if (left > 0) "已确认这一次，还剩 $left 个时刻"
                            else "「${t.name}」今天全部时刻都完成了"
                        )
                    }
                    if (!t.allTimesConfirmed(rec)) {
                        // 还剩时刻没确认：这条**留在清单里**，但必须重渲染——
                        // 以前这里直接 return，bind() 永远不执行，已确认的时刻
                        // 在列表里还是圆圈，只有 toast 弹一下（用户 2026-10-26 实测）
                        bind()
                        return@withContext
                    }
                }
                pending = pending.filter { it.id != t.id }
                if (pending.isEmpty()) {
                    RingService.stop(this@AlarmRingActivity)
                    finish()
                } else {
                    bind()
                }
            }
        }
    }

    /**
     * 多时刻任务：只确认「当前时间匹配的那一个时刻」。
     * 匹配口径：已到点还没确认的取最晚一个，都没到点取最近的未确认时刻。
     * 该时刻已确认过就**不动作**（返回 null）——
     * 防止重复点击把后面的时刻顺带确认掉。
     */
    private suspend fun confirmOneTime(t: Task): TaskRecord? {
        val dao = AppDatabase.get(this@AlarmRingActivity).taskDao()
        val todayKey = AppDatabase.todayKey()
        val total = t.timesOn(todayKey).size
        var skipped = false
        val updated = CheckIn.update(this@AlarmRingActivity, dao, t) { rec ->
            val idx = focusTimeIdx(t, rec)
            if (idx < 0 || t.timeConfirmed(rec, idx)) {
                skipped = true
                rec
            } else {
                rec.confirmTime(idx, total)
            }
        }
        return if (skipped) null else updated
    }

    /** 多时刻任务：当前该关注的时刻下标（全部已确认返回 -1） */
    private fun focusTimeIdx(t: Task, rec: TaskRecord?): Int {
        val nowMin = Calendar.getInstance().let {
            it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE)
        }
        val times = t.timesOn(AppDatabase.todayKey())
        val unconfirmed = times.indices.filter { !t.timeConfirmed(rec, it) }
        // 只认「已到点」的那一个：都已经确认过就返回 -1（不动作）。
        // 以前还有「取未来第一个」的兜底，导致重复点完成会顺手把下一个时刻也确认掉（v3.34.112）
        return unconfirmed.lastOrNull { times[it] <= nowMin } ?: -1
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    /**
     * 催促阶段的「我知道了」：只是关掉铃声，**不标记完成**。
     * 检查有没有做完是复查阶段的事 —— 这才是这套提醒该有的语义。
     */
    private fun acknowledge() {
        RingService.stop(this)
        finish()
    }

    /**
     * 「完成」按钮（复查阶段）：只完成**正在响的这一条**任务。
     * 多时刻任务 = 确认它的所有时刻（v3.34.21 语义保留）。
     * v3.34.110：不再把今天其他没到截止的任务一并确认（用户 2026-11-04 反馈不合理）。
     */
    private fun completeCurrent() {
        tickHaptic()
        val target = pending.firstOrNull { it.id == taskId }
            ?: pending.firstOrNull { it.id in currentIds }
            ?: return acknowledge()
        CoroutineScope(Dispatchers.IO).launch {
            completeAllTimes(target)
            withContext(Dispatchers.Main) { acknowledge() }
        }
    }

    /**
     * 确认一条任务的**全部**时刻（响铃页「完成」按钮用）。
     * 多时刻任务逐个确认完 → completed 才为真；单时刻任务等价于 [complete]。
     */
    private suspend fun completeAllTimes(t: Task): TaskRecord? {
        if (!t.isMultiTime) return complete(t)
        val dao = AppDatabase.get(this@AlarmRingActivity).taskDao()
        val todayKey = AppDatabase.todayKey()
        val total = t.timesOn(todayKey).size
        return CheckIn.update(this@AlarmRingActivity, dao, t) { rec ->
            var r = rec
            for (idx in 0 until total) {
                if (!t.timeConfirmed(r, idx)) r = r.confirmTime(idx, total)
            }
            r
        }
    }

    /**
     * 确认一条任务；返回落库后的记录（没写记录时返回 null）。
     * 多时刻任务只确认触发 / 最早未确认的**那一个**时刻，全部确认才置 completed。
     */
    private suspend fun complete(t: Task): TaskRecord? {
        val dao = AppDatabase.get(this@AlarmRingActivity).taskDao()
        val todayKey = AppDatabase.todayKey()
        if (t.isMultiTime) {
            val total = t.timesOn(todayKey).size
            return CheckIn.update(this@AlarmRingActivity, dao, t) { rec ->
                val idx = if (t.id == taskId) timeIdx
                else t.timesOn(todayKey).indices.firstOrNull { !t.timeConfirmed(rec, it) } ?: 0
                rec.confirmTime(idx, total)
            }
        }
        val done = if (t.targetCount > 0) t.targetCount else 0
        // 读旧记录再改字段：保住清单勾选与时刻条目（v3.34.21）
        return CheckIn.update(this@AlarmRingActivity, dao, t) {
            it.copy(completed = true, doneCount = done)
        }
    }

    /** 最早一个还没确认的时刻下标（全部确认过返回 0） */
    private fun earliestUnconfirmedIdx(t: Task): Int {
        val rec = records[t.id]
        return t.timesOn(AppDatabase.todayKey()).indices
            .firstOrNull { !t.timeConfirmed(rec, it) } ?: 0
    }

    private fun snooze() {
        RingService.stop(this)
        // 只延后正在响的这一批：把当天所有未完成任务都排到 5 分钟后，
        // 会把它们真正该响的提醒提前作废（v3.34.117）。
        // 从通知进来时清单可能还没加载，退回 intent 里的 taskId。
        val targets = pending.filter { it.id in currentIds }.map { it.id }
            .ifEmpty { if (taskId > 0) listOf(taskId) else emptyList() }
        targets.forEach { SnoozeHelper.schedule(this, it, type, timeIdx) }
        finish()
    }

    @Suppress("DEPRECATION")
    private fun isLocked(): Boolean {
        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        return km.isKeyguardLocked
    }

    override fun onBackPressed() {
        // 响铃页不允许返回键关掉，必须做选择 —— 保持响铃，什么都不做
    }
}
