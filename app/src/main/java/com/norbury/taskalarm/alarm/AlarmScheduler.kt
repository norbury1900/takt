package com.norbury.taskalarm.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.norbury.taskalarm.data.AppDatabase
import com.norbury.taskalarm.data.QuietWindow
import com.norbury.taskalarm.data.QuietWindows
import com.norbury.taskalarm.data.RepeatRule
import com.norbury.taskalarm.data.Task
import com.norbury.taskalarm.data.TaskDao
import com.norbury.taskalarm.util.Permissions
import com.norbury.taskalarm.util.Prefs
import java.util.Calendar

/**
 * 闹钟调度器 —— 整个 App 的核心。
 *
 * 职责：把每条任务的全部提醒时间点换算成绝对时间，用 AlarmManager 排定。
 * 到点由 [AlarmReceiver] 再判断"到底该不该响"。
 *
 * v3.27 起支持「每天多个触发时刻」（统一响铃期间模型）：
 * 一条任务每天可有 N 个时刻（固定列表或间隔循环），催促/复查对每个时刻统一生效，
 * 所以槽位上限 = 天数 × 时刻数 × (催促 + 复查)。
 *
 * 关键实现选择：用 setAlarmClock() 而不是 setExact()。
 *   - setAlarmClock 会被系统当成"真正的闹钟"，在状态栏显示下一个闹钟图标
 *   - 唤醒优先级最高，可穿透 Doze 省电模式
 * 若无精确闹钟权限，则降级为 setAndAllowWhileIdle()，仍会响但时间可能漂移几分钟。
 */
object AlarmScheduler {

    private const val TAG = "AlarmScheduler"

    /**
     * 所有 AlarmManager 增删都投到这一个线程（v3.34.113）。
     * 原来每次重排 new 一个 Thread：onResume 与打卡几乎同时触发时，B 的取消会删掉
     * A 刚排上的闹钟，表现为某条任务当天不响。串行后顺序确定，行为与原先一致。
     */
    private val serial = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "alarm-scheduler").apply { isDaemon = true }
    }

    /** 请求码偏移：同一个任务的催促与复查用不同 requestCode，避免互相覆盖 */
    private const val TYPE_PRE = 0
    private const val TYPE_POST = 1

    /**
     * 排未来多少天内的闹钟。
     *
     * 原来是 8 天 —— 这有个真实漏洞：房租这类「每 3 个月」的任务，
     * 中间几十天不会触发也就不会补排，8 天后闹钟就排空了，到日子反而不响。
     * 改成 30 天，既覆盖低频任务，数量也可控。
     * 每次响铃后 AlarmReceiver 会补排，长期不开 App 也不断档。
     */
    private const val HORIZON_DAYS = 30

    /** 30 天内没有触发点时，向前找多久（季度房租 / 生日这类低频任务的兜底窗口） */
    private const val FAR_HORIZON_DAYS = 400

    /** 每类型槽位上限（天数 × 时刻数的理论极值 1440，留余量） */
    private const val MAX_SLOTS = 2048

    /**
     * 重排所有任务的闹钟。建议在以下时机调用：
     *  - App 启动 / 回到前台
     *  - 任务增删改后
     *  - 开机、应用更新后
     *  - 精确闹钟权限状态变化后
     */
    fun rescheduleAll(context: Context) {
        serial.execute {
            try {
                val dao = AppDatabase.get(context).taskDao()
                val tasks = dao.getEnabledSync()

                // 顺手记下最近的一次提醒，设置页显示出来 —— 让用户能确认"闹钟真的排上了"
                var soonest = Long.MAX_VALUE
                var soonestName = ""
                tasks.forEach { t ->
                    val first = schedule(context, t)
                    if (first != null && first < soonest) {
                        soonest = first
                        soonestName = t.name
                    }
                }
                Prefs.setNextAlarm(
                    context,
                    if (soonest == Long.MAX_VALUE) 0L else soonest,
                    soonestName
                )
                Log.i(TAG, "已重排 ${tasks.size} 条任务的闹钟，下一次 $soonestName @ $soonest")

                // 补响：闹钟被系统吞掉（强杀、清理后台、广播被拦）时，
                // 打开 App 的这个时机就是最后的补救点——把今天错过又没做的提醒补上
                catchUpMissed(context, dao, tasks)
            } catch (e: Exception) {
                Log.e(TAG, "重排闹钟失败", e)
            }
        }
    }

    /** 单条任务重排（响铃后补排）：与全量重排共用同一个串行线程 */
    fun scheduleLater(context: Context, task: Task) {
        serial.execute { runCatching { schedule(context, task) } }
    }

    /**
     * 排定单条任务的全部闹钟；返回其中最早的一次，供上层统计。
     *
     * 先按上次记录的槽位数取消旧闹钟（data 稳定，可精确匹配），再排新的。
     */
    fun schedule(context: Context, task: Task): Long? {
        if (!task.enabled) return null
        cancelSlots(context, task.id)

        val now = System.currentTimeMillis()
        var soonest: Long? = null

        // 免打扰窗口（考试/会议）：落在窗口内的响铃整体后移到窗口尾。
        // 排定与补响必须用同一套窗口，否则取消/补响会和已排定的闹钟对不上。
        val quiet = QuietWindows.parse(Prefs.quietWindows(context))

        // -1 = 关闭；0 = 准时；>0 = 提前/延后 N 分钟。
        // v3.34.21 起**逐时刻各取各的偏移**（Task.timeOffsets），没单独配置的回落任务级。
        // fireOn 拿到时刻下标，才能算出该用哪一套偏移。
        if (task.hasPreReminder()) {
            val occ = occurrences(task, now, quiet) { _, time ->
                val off = task.preOffsetOf(time)
                if (off < 0) -1 else time - off // -1 = 该时刻单独关掉了催促
            }
            occ.forEachIndexed { slot, (triggerAt, timeIdx) ->
                setOne(context, task, triggerAt, TYPE_PRE, slot, timeIdx)
                soonest = minOf(soonest ?: Long.MAX_VALUE, triggerAt)
            }
            Prefs.setSlotCount(context, task.id, TYPE_PRE, occ.size)
        }
        if (task.hasPostReminder()) {
            val occ = occurrences(task, now, quiet) { _, time ->
                val off = task.postOffsetOf(time)
                if (off < 0) -1 else time + off
            }
            occ.forEachIndexed { slot, (triggerAt, timeIdx) ->
                setOne(context, task, triggerAt, TYPE_POST, slot, timeIdx)
                soonest = minOf(soonest ?: Long.MAX_VALUE, triggerAt)
            }
            Prefs.setSlotCount(context, task.id, TYPE_POST, occ.size)
        }
        return soonest
    }

    /**
     * 枚举未来 [HORIZON_DAYS] 天内、该任务某类提醒的全部触发点。
     * 返回 (触发时刻毫秒, 当天时刻下标) 对，按时间升序——下标就是 PendingIntent 的槽位。
     * [fireOn] 把「当天第几个时刻 / 该时刻本身」换算成「响铃时刻」（催促减、复查加偏移，
     * 偏移逐时刻取值，见 [Task.preOffsetOf] / [Task.postOffsetOf]）。
     * [quiet] 免打扰窗口（v3.34.16）：算完响铃分钟再整体后移，与补响 [catchUpMissed] 同一套口径。
     */
    private fun occurrences(
        task: Task,
        now: Long,
        quiet: List<QuietWindow>,
        fireOn: (Int, Int) -> Int
    ): List<Pair<Long, Int>> =
        scan(task, now, quiet, fireOn, HORIZON_DAYS).ifEmpty {
            // 季度房租、生日这类低频任务：30 天内没有触发点不代表不用排，
            // 以前就这样把闹钟排空了，长期不开 App 到日子根本不响（v3.34.112）
            scan(task, now, quiet, fireOn, FAR_HORIZON_DAYS).take(8)
        }

    /** 见 [occurrences]；[fireOn] 返回 -1 表示该时刻的这类提醒关闭了 */
    private fun scan(
        task: Task,
        now: Long,
        quiet: List<QuietWindow>,
        fireOn: (Int, Int) -> Int,
        horizonDays: Int
    ): List<Pair<Long, Int>> {
        val result = ArrayList<Pair<Long, Int>>()
        val cal = Calendar.getInstance()

        for (dayOffset in 0 until horizonDays) {
            cal.timeInMillis = now
            cal.add(Calendar.DAY_OF_YEAR, dayOffset)
            // 统一判定：按星期的查法定日历，按日期的查账期
            val key = AppDatabase.keyOf(cal.time)
            if (!RepeatRule.shouldTriggerTask(task, key, cal.get(Calendar.DAY_OF_WEEK))) continue

            task.timesOn(key).forEachIndexed { timeIdx, timeMin ->
                if (result.size >= MAX_SLOTS) return result
                val fire = fireOn(timeIdx, timeMin)
                if (fire < 0) return@forEachIndexed
                // 跨午夜：以前对分钟取余会把 00:30 提前 60 分排成「当天 23:30」，
                // 日期错一天；把日期跟着偏移又会把提醒记到相邻日（AlarmReceiver 按响铃当天
                // 查记录/判星期，周一的催促会在周日被跳过）。现在统一裁到任务当天：
                // 00:15 提前 30 分 → 当天 00:00，23:00 复查 120 分 → 当天 23:59。
                // 代价是跨夜时比配置时刻早一点，但提醒不会漏、记录不会写错天。
                // ponytail: 若要精确保留跨日时刻，需把任务日期键贯通到响铃页。
                val m = QuietWindows.shiftFor(quiet, key, fire.coerceIn(0, 1439))
                cal.set(Calendar.HOUR_OF_DAY, m / 60)
                cal.set(Calendar.MINUTE, m % 60)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                val t = cal.timeInMillis
                if (t > now + 1000) result.add(t to timeIdx)
            }
        }
        return result
    }

    private fun setOne(
        context: Context, task: Task, triggerAt: Long, type: Int, slot: Int, timeIdx: Int
    ) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pendingIntent(context, task.id, type, slot, triggerAt, timeIdx)

        try {
            if (Permissions.canScheduleExactAlarms(context)) {
                // 最高优先级：系统按闹钟对待，穿透 Doze
                am.setAlarmClock(
                    AlarmManager.AlarmClockInfo(triggerAt, showIntent(context, task, type)),
                    pi
                )
            } else {
                // 降级：不精确但会在低电耗模式下唤醒
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
        } catch (e: SecurityException) {
            // Android 14+ 未授权时会抛，降级保住功能
            Log.w(TAG, "精确闹钟被拒，降级为不精确闹钟", e)
            try {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } catch (e2: Exception) {
                Log.e(TAG, "排定闹钟彻底失败", e2)
            }
        } catch (e: Exception) {
            Log.e(TAG, "排定闹钟失败", e)
        }
    }

    /**
     * 取消某条任务已排定的全部闹钟（编辑/删除任务时用）。
     * 按上次记录的槽位数枚举——data 稳定，同参 Intent 可精确匹配；
     * 没有记录时回退到旧版单时刻模式的上限（v3.26.9 每类型最多 30 个）。
     */
    fun cancel(context: Context, taskId: Long) {
        // 与重排同队列：调用方「先 cancel 再 rescheduleAll」的顺序不会被并发打乱
        serial.execute { cancelNow(context, taskId) }
    }

    private fun cancelNow(context: Context, taskId: Long) {
        for (type in intArrayOf(TYPE_PRE, TYPE_POST)) {
            val n = Prefs.slotCount(context, taskId, type)
            for (slot in 0 until n.coerceAtMost(MAX_SLOTS)) {
                pendingIntentOrNull(context, taskId, type, slot)?.let {
                    val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                    am.cancel(it)
                    it.cancel()
                }
            }
            Prefs.setSlotCount(context, taskId, type, 0)
        }
    }

    /** schedule() 开头用：只取消、不动计数（马上会写入新计数） */
    private fun cancelSlots(context: Context, taskId: Long) {
        for (type in intArrayOf(TYPE_PRE, TYPE_POST)) {
            val n = Prefs.slotCount(context, taskId, type)
            for (slot in 0 until n.coerceAtMost(MAX_SLOTS)) {
                pendingIntentOrNull(context, taskId, type, slot)?.let {
                    val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                    am.cancel(it)
                    it.cancel()
                }
            }
        }
    }

    /**
     * 补响检查：今天的提醒时间已过、任务没完成、且这一时刻今天还没响过 → 立刻拉起响铃。
     * 与正常响铃共用 Prefs 的「今日已响」标记，不会双响。
     * 宽限窗口见 [Task.CATCH_UP_GRACE_MIN]：超过就不补了（中午的任务晚上补响没有意义）。
     * 多时刻任务逐时刻补：8:00 那次漏了、10:00 那次确认了，只补 8:00。
     */
    private fun catchUpMissed(context: Context, dao: TaskDao, tasks: List<Task>) {
        val todayKey = AppDatabase.todayKey()
        val dow = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
        val nowCal = Calendar.getInstance()
        val nowMin = nowCal.get(Calendar.HOUR_OF_DAY) * 60 + nowCal.get(Calendar.MINUTE)
        // 与 schedule() 同一套免打扰窗口：补响时间 = 排定时间，两边必须一致
        val quiet = QuietWindows.parse(Prefs.quietWindows(context))

        tasks.forEach { t ->
            try {
                if (!RepeatRule.shouldTriggerTask(t, todayKey, dow)) return@forEach
                val record = dao.getRecordSync(todayKey, t.id)
                // 整天完成才跳过：多时刻任务只完成了一个时刻，剩下的时刻仍要补响
                if (t.allTimesConfirmed(record)) return@forEach
                val multi = t.timeSpec.isNotBlank()

                // 与 schedule() 同一套换算：每个时刻的催促 / 复查 (type, timeIdx, fireMin)
                // v3.34.21：偏移逐时刻取（Task.timeOffsets），不再是任务级一个值
                val candidates = ArrayList<Triple<Int, Int, Int>>()
                t.timesOn(todayKey).forEachIndexed { timeIdx, timeMin ->
                    val pre = t.preOffsetOf(timeMin)
                    if (pre >= 0) {
                        val raw = ((timeMin - pre) % 1440 + 1440) % 1440
                        candidates.add(
                            Triple(TYPE_PRE, timeIdx, QuietWindows.shiftFor(quiet, todayKey, raw))
                        )
                    }
                    val post = t.postOffsetOf(timeMin)
                    if (post >= 0) {
                        val raw = ((timeMin + post) % 1440 + 1440) % 1440
                        candidates.add(
                            Triple(TYPE_POST, timeIdx, QuietWindows.shiftFor(quiet, todayKey, raw))
                        )
                    }
                }
                candidates.forEach { (type, timeIdx, fireMin) ->
                    val late = Task.catchUpLateMin(fireMin, nowMin) ?: return@forEach
                    if (multi && t.timeConfirmed(record, timeIdx)) return@forEach
                    if (Prefs.wasFired(context, t.id, todayKey, type, timeIdx)) return@forEach
                    Log.i(TAG, "补响：「${t.name}」迟到 $late 分钟")
                    RingService.start(context, t.id, type, timeIdx)
                }
            } catch (e: Exception) {
                Log.e(TAG, "补响检查失败 taskId=${t.id}", e)
            }
        }
    }

    /** 到点触发时拉起的广播。data 必须稳定（不含 triggerAt），否则 cancel 永远匹配不到 */
    private fun pendingIntent(
        context: Context, taskId: Long, type: Int, slot: Int, triggerAt: Long, timeIdx: Int
    ): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = AlarmReceiver.ACTION_ALARM
            putExtra(AlarmReceiver.EXTRA_TASK_ID, taskId)
            putExtra(AlarmReceiver.EXTRA_TYPE, type)
            putExtra(AlarmReceiver.EXTRA_TIME_INDEX, timeIdx)
            // 触发时刻走 extra 而不是拼进 data：filterEquals 比对 data 字符串，
            // 可变量一旦进 data，取消/更新就永远匹配不到（v3.26.8 前的真实缺陷）
            putExtra(AlarmReceiver.EXTRA_TRIGGER_AT, triggerAt)
            // 槽位（同类提醒内的序号）让每个时间点的 PendingIntent 互不相同
            data = android.net.Uri.parse("taskalarm://alarm/$taskId/$type/$slot")
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode(taskId, type, slot),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun pendingIntentOrNull(
        context: Context, taskId: Long, type: Int, slot: Int
    ): PendingIntent? {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = AlarmReceiver.ACTION_ALARM
            putExtra(AlarmReceiver.EXTRA_TASK_ID, taskId)
            putExtra(AlarmReceiver.EXTRA_TYPE, type)
            data = android.net.Uri.parse("taskalarm://alarm/$taskId/$type/$slot")
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode(taskId, type, slot),
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** 点击系统状态栏"下一个闹钟"图标时打开的任务页 */
    private fun showIntent(context: Context, task: Task, type: Int): PendingIntent {
        val intent = Intent(context, com.norbury.taskalarm.MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return PendingIntent.getActivity(
            context,
            requestCode(task.id, type, MAX_SLOTS - 1),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** 请求码：任务 id × 4096 + 类型 × 2048 + 槽位，各字段互不侵占 */
    private fun requestCode(taskId: Long, type: Int, slot: Int): Int =
        (taskId.toInt() * (MAX_SLOTS * 2)) + (type * MAX_SLOTS) + slot
}
