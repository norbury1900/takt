package com.norbury.taktu.util

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.util.Log
import androidx.core.content.ContextCompat
import com.norbury.taktu.data.AppDatabase
import com.norbury.taktu.data.RepeatRule
import com.norbury.taktu.data.Task
import java.util.Calendar
import java.util.TimeZone

/**
 * 小米生态联动桥接。
 *
 * 调研结论（小米 15 / HyperOS 2）：
 *   小米时钟   ✅ 可联动 —— 走标准 AlarmClock.ACTION_SET_ALARM，小米时钟会响应它
 *   小米日历   ✅ 可联动 —— 走标准 CalendarContract Provider，小米日历基于它实现
 *   小米待办   ❌ 不可联 —— 走 i.mi.com/todo 云端接口，需小米账号登录态，第三方无授权通道
 *   小米笔记   ❌ 不可联 —— 无公开 API
 *
 * 联动的价值：把任务写进小米时钟后，即使本 App 被系统杀掉后台、甚至卸载，
 * 闹钟依然由小米时钟保证会响。这是对本 App 自身闹钟的第二重保险。
 */
object MiLinkBridge {

    private const val TAG = "MiLinkBridge"

    /** 本 App 写入日历时使用的标记，便于识别与清理 */
    private const val CALENDAR_TAG = "taktu"

    /* ============ 权限 ============ */

    fun hasCalendarPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.WRITE_CALENDAR
                ) == PackageManager.PERMISSION_GRANTED

    /**
     * 常见时钟包名，用于隐式 Intent 查不到时兜底探测。
     * 小米 / HyperOS 的时钟通常是 com.android.deskclock。
     */
    private val KNOWN_CLOCK_PACKAGES = listOf(
        "com.android.deskclock",
        "com.miui.deskclock",
        "com.google.android.deskclock",
        "com.android.alarmclock"
    )

    /**
     * 小米时钟联动不需要额外权限（SET_ALARM 是普通权限，Manifest 声明即可）。
     *
     * 注意 Android 11+ 的包可见性限制：必须在 Manifest 的 &lt;queries&gt; 里声明
     * SET_ALARM / SHOW_ALARMS，否则 resolveActivity 恒为 null，明明装了时钟也检测不到。
     * 这里额外用已知包名兜底，双保险。
     */
    fun canLinkClock(context: Context): Boolean = detectClockApp(context) != null

    /** 返回检测到的时钟包名，找不到返回 null（会显示在环境自检页便于排查） */
    fun detectClockApp(context: Context): String? {
        val pm = context.packageManager

        // 1) 标准 Intent 探测（依赖 Manifest 的 <queries> 声明）
        runCatching {
            Intent(AlarmClock.ACTION_SET_ALARM).resolveActivity(pm)?.packageName
        }.getOrNull()?.let { return it }
        runCatching {
            Intent(AlarmClock.ACTION_SHOW_ALARMS).resolveActivity(pm)?.packageName
        }.getOrNull()?.let { return it }

        // 2) 兜底：直接查已知时钟包是否已安装（同样需要 <queries> 里的 package 声明）
        return KNOWN_CLOCK_PACKAGES.firstOrNull { pkg ->
            runCatching { pm.getPackageInfo(pkg, 0) }.isSuccess
        }
    }

    /* ============ 小米时钟 ============ */

    /**
     * 把任务写进小米时钟，创建一条真实系统闹钟。
     *
     * **响铃时刻 = 催促点**（`preAlertMin`），不是截止时刻。
     * 这是 v3.34.17 修掉的 bug：原来写的是 `deadlineMin`，于是「08:30 开考、
     * 提前 90 分钟」的任务在小米闹钟里被写成 08:30 响 —— 和 App 里的实际
     * 响铃时刻（07:00）对不上。现在写的是真正会响的那一刻。
     *
     * 语义差异：小米时钟的闹钟是「按星期重复」或「一次性」，
     * 而本 App 的提醒是「到点先看做没做，没做才响」。
     * 所以这里写入的是**实际响铃点**，把它当作最后兜底闹钟。
     *
     * @param withUi false 表示跳过小米时钟的确认界面直接写入（EXTRA_SKIP_UI）
     */
    fun pushToClock(context: Context, task: Task, withUi: Boolean = false): Boolean {
        // 按日期重复的任务（房租这类）不写系统闹钟：
        // SET_ALARM 只能"按星期重复"或"一次性"，一次性会在明天误响，
        // 表达不了"每 3 个月后的 21 号"。日历联动（RRULE）可以，见 pushToCalendar。
        if (task.repeatType == RepeatRule.TYPE_DATE ||
            task.repeatType == RepeatRule.TYPE_LUNAR
        ) {
            Log.i(TAG, "按日期 / 农历重复的任务走日历联动，不写系统时钟")
            return false
        }
        // 一次性任务同样写不进系统时钟：AlarmClock 没有"某一天一次"这个概念，
        // 硬写会变成"每天重复"（软考任务就是这么在小米闹钟里变成每天 08:30 的）。
        // 改走系统日历 —— RRULE 能精确定位到那一天。
        if (task.repeatType == RepeatRule.TYPE_ONCE) {
            Log.i(TAG, "一次性任务走日历联动（系统时钟表达不了「某一天一次」）")
            val ok = pushToCalendar(context, task)
            Prefs.appendLinkLog(context, "${task.id}|clock->calendar|$ok|一次性任务改走日历")
            return ok
        }
        return try {
            // 响铃时刻：催促点（preAlertMin）。催促关闭时退化为截止时刻本身。
            // 多时刻任务取**最早那个**时刻的催促点：系统闹钟只能写一条，
            // 写最早的那次才不至于漏掉第一次（v3.34.21）。
            val firstTime = task.timesOn(AppDatabase.todayKey()).firstOrNull() ?: task.deadlineMin
            val preOfFirst = task.preOffsetOf(firstTime)
            val fireMin = if (preOfFirst >= 0) firstTime - preOfFirst else firstTime
            val norm = ((fireMin % 1440) + 1440) % 1440
            val hour = norm / 60
            val minute = norm % 60

            // 重复：把位掩码转成小米时钟认识的星期列表
            val days = ArrayList<Int>()
            // Calendar 中 SUNDAY=1 .. SATURDAY=7，与 AlarmClock 期望一致
            for (dow in Calendar.SUNDAY..Calendar.SATURDAY) {
                if (RepeatRule.matches(task.repeatMask, dow)) {
                    days.add(dow)
                }
            }

            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                putExtra(AlarmClock.EXTRA_MESSAGE, task.name)
                putExtra(AlarmClock.EXTRA_VIBRATE, true)
                putExtra(AlarmClock.EXTRA_SKIP_UI, !withUi)
                if (days.isNotEmpty()) putIntegerArrayListExtra(AlarmClock.EXTRA_DAYS, days)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            if (intent.resolveActivity(context.packageManager) == null) {
                // 隐式 Intent 查不到时，改为显式指定检测到的时钟包名再试
                val pkg = detectClockApp(context)
                if (pkg == null) {
                    Log.w(TAG, "设备上没有能响应设置闹钟的应用")
                    Prefs.appendLinkLog(context, "${task.id}|clock|fail|设备上没有时钟应用")
                    return false
                }
                intent.setPackage(pkg)
            }
            context.startActivity(intent)
            // 记下实际写进去的时刻——软考那次「8:30 vs 7:00」就是因为没有这条记录，
            // 只能靠用户肉眼发现。这里把「写成了几点」明确留痕，PC 端可查。
            val daysDesc = if (days.isEmpty()) "单次" else "每周${days.size}天"
            Prefs.appendLinkLog(
                context, "${task.id}|clock|ok|${Task.formatMin(norm)} $daysDesc"
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "写入小米时钟失败", e)
            Prefs.appendLinkLog(context, "${task.id}|clock|fail|${e.message ?: "异常"}")
            false
        }
    }

    /** 打开时钟的闹钟列表页；打不开就退而打开时钟主界面 */
    fun openClockAlarms(context: Context): Boolean {
        // 先试系统标准的闹钟列表页
        runCatching {
            val i = Intent(AlarmClock.ACTION_SHOW_ALARMS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (i.resolveActivity(context.packageManager) != null) {
                context.startActivity(i)
                return true
            }
        }
        // 兜底：直接拉起时钟 App 主界面
        val pkg = detectClockApp(context) ?: return false
        val launch = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(launch)
            true
        }.onFailure { Log.e(TAG, "打开时钟失败", it) }.getOrDefault(false)
    }

    /* ============ 小米日历 ============ */

    /** 找到本 App 专用的日历（没有就创建），返回日历 ID */
    private fun ensureCalendar(context: Context): Long? {
        if (!hasCalendarPermission(context)) return null

        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.NAME
        )
        val selection = "${CalendarContract.Calendars.NAME} = ?"
        val args = arrayOf(CALENDAR_TAG)

        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI, projection, selection, args, null
        )?.use { cur ->
            if (cur.moveToFirst()) {
                return cur.getLong(0)
            }
        }
        return createCalendar(context)
    }

    private fun createCalendar(context: Context): Long? {
        val values = ContentValues().apply {
            put(CalendarContract.Calendars.ACCOUNT_NAME, CALENDAR_TAG)
            put(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(CalendarContract.Calendars.NAME, CALENDAR_TAG)
            put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, "Taktu")
            put(CalendarContract.Calendars.CALENDAR_COLOR, 0xFF7A5C.toInt())
            put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER)
            put(CalendarContract.Calendars.OWNER_ACCOUNT, CALENDAR_TAG)
            put(CalendarContract.Calendars.VISIBLE, 1)
            put(CalendarContract.Calendars.SYNC_EVENTS, 1)
        }

        // 通过追加查询参数，以「同步适配器」身份写入，否则 ACCOUNT_TYPE_LOCAL 日历无法创建
        val uri = CalendarContract.Calendars.CONTENT_URI.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, CALENDAR_TAG)
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            .build()

        return try {
            val result = context.contentResolver.insert(uri, values)
            result?.lastPathSegment?.toLongOrNull()
        } catch (e: Exception) {
            Log.e(TAG, "创建日历失败", e)
            null
        }
    }

    /**
     * 把任务写进小米日历。
     *
     * 写入的是一条日程：**起点 = 实际响铃点**（催促点，没有催促就是截止时刻），
     * 终点 = 截止时刻，并在起点上挂一次日历提醒。
     *
     * v3.34.18 修：原来起点写死「截止前 30 分钟」、提醒写死「开始前 30 分钟」，
     * 于是软考（08:30 开考、提前 90 分钟）日历提醒落在 07:30 —— 和 App 里
     * 真正响铃的 07:00 又对不上。现在起点/提醒都直接取实际响铃点。
     */
    fun pushToCalendar(context: Context, task: Task): Boolean {
        if (!hasCalendarPermission(context)) {
            Log.w(TAG, "无日历权限")
            Prefs.appendLinkLog(context, "${task.id}|calendar|fail|无日历权限")
            return false
        }
        val calId = ensureCalendar(context)
            ?: run {
                Prefs.appendLinkLog(context, "${task.id}|calendar|fail|建不出自定义日历")
                return false
            }

        // 找是否已写过这条任务，避免重复插入
        val existingId = findEventId(context, task.id)
        if (existingId != null) {
            val ok = updateEvent(context, existingId, task)
            if (ok) logCalendarWrite(context, task)
            else Prefs.appendLinkLog(context, "${task.id}|calendar|fail|更新已有事件失败")
            return ok
        }

        val (start, end) = eventStartAndEnd(task)
            ?: run {
                Prefs.appendLinkLog(context, "${task.id}|calendar|fail|算不出到期日")
                return false
            }

        val tz = TimeZone.getDefault().id
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calId)
            put(CalendarContract.Events.TITLE, task.name)
            put(
                CalendarContract.Events.DESCRIPTION,
                "来自「Taktu」\n目标：${if (task.targetCount > 0) "${task.targetCount} 次" else "打勾完成"}"
            )
            put(CalendarContract.Events.DTSTART, start)
            put(CalendarContract.Events.DTEND, end)
            put(CalendarContract.Events.EVENT_TIMEZONE, tz)
            put(CalendarContract.Events.ALL_DAY, 0)
            put(CalendarContract.Events.HAS_ALARM, 1)
            // 农历单日事件没有 RRULE（空串不写字段，写了部分机型会解析失败）
            if (buildRRule(task).isNotEmpty()) {
                put(CalendarContract.Events.RRULE, buildRRule(task))
            }
            // 用官方自定义字段存任务 ID（SYNC_DATA1 是同步适配器专列，
            // 普通应用写入在部分机型上会失败 —— 疑似日历联动失效的根因）
            put(CalendarContract.Events.CUSTOM_APP_PACKAGE, context.packageName)
            put(CalendarContract.Events.CUSTOM_APP_URI, "$CALENDAR_TAG://task/${task.id}")
        }

        return try {
            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
                ?: run {
                    Prefs.appendLinkLog(context, "${task.id}|calendar|fail|插入事件返回空")
                    return false
                }
            val eventId = uri.lastPathSegment?.toLongOrNull()
                ?: run {
                    Prefs.appendLinkLog(context, "${task.id}|calendar|fail|拿不到事件 id")
                    return false
                }
            // 提醒必须真的落上「闹钟式」——写失败要留痕（GET /links 可见），
            // 不能只 catch 日志：用户看到的就是「日历里有事件、到点不响」
            val reminderOk = addReminder(context, eventId, 0)
            verifyReminder(context, task.id, eventId, reminderOk)
            logCalendarWrite(context, task)
            true
        } catch (e: Exception) {
            Log.e(TAG, "写入日历失败", e)
            Prefs.appendLinkLog(context, "${task.id}|calendar|fail|${e.message ?: "异常"}")
            false
        }
    }

    /** 日历写入留痕：记下事件落在哪一天、几点开始（= 实际响铃点） */
    private fun logCalendarWrite(context: Context, task: Task) {
        val span = eventStartAndEnd(task)
        val desc = if (span == null) {
            "已写入（时间待定）"
        } else {
            val c = java.util.Calendar.getInstance().apply { timeInMillis = span.first }
            val day = "%04d-%02d-%02d".format(
                c.get(java.util.Calendar.YEAR),
                c.get(java.util.Calendar.MONTH) + 1,
                c.get(java.util.Calendar.DAY_OF_MONTH)
            )
            val hh = c.get(java.util.Calendar.HOUR_OF_DAY)
            val mm = c.get(java.util.Calendar.MINUTE)
            "$day %02d:%02d".format(hh, mm)
        }
        Prefs.appendLinkLog(context, "${task.id}|calendar|ok|$desc")
    }

    private fun updateEvent(context: Context, eventId: Long, task: Task): Boolean {
        val (start, end) = eventStartAndEnd(task) ?: return false
        val values = ContentValues().apply {
            put(CalendarContract.Events.TITLE, task.name)
            put(
                CalendarContract.Events.DESCRIPTION,
                "来自「Taktu」\n目标：${if (task.targetCount > 0) "${task.targetCount} 次" else "打勾完成"}"
            )
            put(CalendarContract.Events.DTSTART, start)
            put(CalendarContract.Events.DTEND, end)
            // 更新路径也要显式声明「此事件有闹钟」——创建路径一直写着（v3.34.26），
            // 这里漏了：编辑任务再同步走 updateEvent，部分机型上事件会丢闹钟属性
            put(CalendarContract.Events.HAS_ALARM, 1)
            if (buildRRule(task).isNotEmpty()) {
                put(CalendarContract.Events.RRULE, buildRRule(task))
            }
        }
        return try {
            val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
            val ok = context.contentResolver.update(uri, values, null, null) > 0
            // 提醒也要跟着改（老事件挂的是"提前 30 分钟"，现在改成"响铃点"）
            if (ok) {
                clearReminders(context, eventId)
                val reminderOk = addReminder(context, eventId, 0)
                verifyReminder(context, task.id, eventId, reminderOk)
            }
            ok
        } catch (e: Exception) {
            Log.e(TAG, "更新日历事件失败", e)
            false
        }
    }

    /** 删掉某个事件上已有的所有提醒（改提醒前先清，避免叠加出两次） */
    private fun clearReminders(context: Context, eventId: Long) {
        try {
            context.contentResolver.delete(
                CalendarContract.Reminders.CONTENT_URI,
                "${CalendarContract.Reminders.EVENT_ID} = ?",
                arrayOf(eventId.toString())
            )
        } catch (e: Exception) {
            Log.w(TAG, "清理旧日历提醒失败", e)
        }
    }

    private fun addReminder(context: Context, eventId: Long, minutesBefore: Int): Boolean {
        val values = ContentValues().apply {
            put(CalendarContract.Reminders.EVENT_ID, eventId)
            put(CalendarContract.Reminders.MINUTES, minutesBefore)
            // METHOD_ALARM（闹钟式提醒，API 24+）= 日历自带的「闹钟提醒」：
            // 到点像系统闹钟一样响铃，而不是 METHOD_ALERT 那样只弹一条通知
            // （用户 2026-10-22：联动日历时顺便把闹钟提醒打开）。minSdk 26 可直接用
            put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALARM)
        }
        return try {
            context.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, values) != null
        } catch (e: Exception) {
            Log.e(TAG, "添加日历提醒失败", e)
            false
        }
    }

    /**
     * 回读事件上的提醒并留痕（GET /links 可见）。
     *
     * 用户要的是「同步到日历 = 连闹钟提醒一起设上」（2026-10-25）：
     * 只写不验等于赌——MIUI 上「日历有事件、到点不响」历来就是这么来的。
     * 回读确认落成了 ALARM 才算数；ALERT/空/异常全部按 fail 记。
     */
    private fun verifyReminder(context: Context, taskId: Long, eventId: Long, insertOk: Boolean) {
        if (!insertOk) {
            Prefs.appendLinkLog(context, "$taskId|calendar-reminder|fail|提醒插入失败")
            return
        }
        try {
            context.contentResolver.query(
                CalendarContract.Reminders.CONTENT_URI,
                arrayOf(
                    CalendarContract.Reminders.MINUTES,
                    CalendarContract.Reminders.METHOD
                ),
                "${CalendarContract.Reminders.EVENT_ID} = ?",
                arrayOf(eventId.toString()),
                null
            )?.use { c ->
                if (!c.moveToFirst()) {
                    Prefs.appendLinkLog(context, "$taskId|calendar-reminder|fail|回读为空")
                    return
                }
                val min = c.getInt(0)
                val raw = c.getInt(1)
                val method = when (raw) {
                    CalendarContract.Reminders.METHOD_ALARM -> "ALARM"
                    CalendarContract.Reminders.METHOD_ALERT -> "ALERT"
                    CalendarContract.Reminders.METHOD_EMAIL -> "EMAIL"
                    CalendarContract.Reminders.METHOD_SMS -> "SMS"
                    else -> "M$raw"
                }
                Prefs.appendLinkLog(context, "$taskId|calendar-reminder|ok|$method@$min")
            }
        } catch (e: Exception) {
            Prefs.appendLinkLog(context, "$taskId|calendar-reminder|fail|${e.message ?: "异常"}")
        }
    }

    /** 按日期重复任务的下次到期日（含今天）；找不到（已全部结束）返回 null */
    private fun nextDateDueKey(task: Task): String? {
        val cal = java.util.Calendar.getInstance()
        repeat(400) {
            val key = "%04d-%02d-%02d".format(
                cal.get(java.util.Calendar.YEAR),
                cal.get(java.util.Calendar.MONTH) + 1,
                cal.get(java.util.Calendar.DAY_OF_MONTH)
            )
            if (RepeatRule.isDateDue(
                    task.anchorDate, task.intervalMonths, task.endDate, key,
                    task.intervalDays, RepeatRule.dayMaskOf(task.repeatMask),
                    task.monthsMask
                )
            ) return key
            cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
        }
        return null
    }

    private fun millisForKey(dateKey: String, minuteOfDay: Int): Long {
        val p = dateKey.split("-").map { it.toIntOrNull() ?: return 0L }
        val c = java.util.Calendar.getInstance()
        c.clear()
        c.set(p[0], p[1] - 1, p[2])
        val m = ((minuteOfDay % 1440) + 1440) % 1440
        c.set(java.util.Calendar.HOUR_OF_DAY, m / 60)
        c.set(java.util.Calendar.MINUTE, m % 60)
        c.set(java.util.Calendar.SECOND, 0)
        c.set(java.util.Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    /**
     * 日历事件的时间区间：[实际响铃点, 截止时刻]。
     *
     * 起点的取法与 App 内的响铃完全同源（`preAlertMin`），这样日历提醒、
     * 系统闹钟、App 内部响铃三者说的是同一个时刻。
     * 一次性任务锚定 `anchorDate`（不能推"下一次"，否则会落到别的日子）。
     *
     * @return null 表示这个任务没有可写的到期日（如一次性任务缺 anchorDate）
     */
    private fun eventStartAndEnd(task: Task): Pair<Long, Long>? {
        val key = when (task.repeatType) {
            // 月度序列的锚点必须是下一个真正的到期日，否则 RRULE 会从错误的月份开始
            RepeatRule.TYPE_DATE -> nextDateDueKey(task) ?: return null
            // RRULE 表达不了农历 → 只写下一次的单日事件；过期后重推任务可续下一年
            RepeatRule.TYPE_LUNAR -> nextLunarDueKey(task) ?: return null
            RepeatRule.TYPE_ONCE -> task.anchorDate.ifBlank { return null }
            // 按星期的：锚点要取下一个真正命中的星期几，
            // 否则 DTSTART 可能落在不重复的日子上，某些日历会多出一条
            else -> nextWeekdayDueKey(task) ?: return null
        }
        // 用**当天**的截止时刻（休息日可能另有时间），不用固定的 deadlineMin
        val deadlineMin = task.deadlineOn(key)
        // 实际响铃点：催促开着 = 催促点；关着 = 截止时刻本身
        val fireMin = if (task.preOffsetMin >= 0) deadlineMin - task.preOffsetMin else deadlineMin
        // 以当天零点为基准做加减：跨零点的响铃点（提前量 > 目标时刻）才能落在前一天
        val base = millisForKey(key, 0)
        val start = base + fireMin * 60_000L
        var end = base + deadlineMin * 60_000L
        if (end <= start) end = start + 30 * 60_000L
        return start to end
    }

    /**
     * 按星期任务的下一次到期日（含今天）：往后扫 14 天找第一个命中重复掩码的日子。
     * 扫不到（掩码全空等异常）返回 null。
     */
    private fun nextWeekdayDueKey(task: Task): String? {
        val cal = java.util.Calendar.getInstance()
        repeat(15) {
            val key = "%04d-%02d-%02d".format(
                cal.get(java.util.Calendar.YEAR),
                cal.get(java.util.Calendar.MONTH) + 1,
                cal.get(java.util.Calendar.DAY_OF_MONTH)
            )
            if (RepeatRule.matches(task.repeatMask, cal.get(java.util.Calendar.DAY_OF_WEEK))) {
                return key
            }
            cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
        }
        return null
    }

    /**
     * 农历任务的下一个到期日（含提前量），向后扫一年。
     * ponytail: 农历 RRULE 不存在，日历侧只做单次事件；过期后重新保存任务即续下一年。
     */
    private fun nextLunarDueKey(task: Task): String? {
        val cal = java.util.Calendar.getInstance()
        repeat(400) {
            val key = "%04d-%02d-%02d".format(
                cal.get(java.util.Calendar.YEAR),
                cal.get(java.util.Calendar.MONTH) + 1,
                cal.get(java.util.Calendar.DAY_OF_MONTH)
            )
            if (RepeatRule.isLunarDueFor(task, key)) {
                return key
            }
            cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
        }
        return null
    }

    /** 把重复规则转成 iCalendar RRULE；按日期的任务生成 FREQ=MONTHLY 序列；农历无 RRULE（返回空串） */
    private fun buildRRule(task: Task): String {
        if (task.repeatType == RepeatRule.TYPE_LUNAR) {
            return ""
        }
        // 一次性任务不能带 RRULE —— 带了就成了"每天重复"（软考任务就是这么变成每天 08:30 的）
        if (task.repeatType == RepeatRule.TYPE_ONCE) {
            return ""
        }
        if (task.repeatType == RepeatRule.TYPE_DATE &&
            task.anchorDate.isNotBlank()
        ) {
            val dom = task.anchorDate.takeLast(2).toIntOrNull()
                ?: return "FREQ=DAILY;COUNT=1"
            val dm = RepeatRule.dayMaskOf(task.repeatMask)
            val days = if (dm != 0) RepeatRule.daysOf(dm).joinToString(",") else dom.toString()
            val sb = if (task.intervalDays > 0) {
                StringBuilder("FREQ=DAILY;INTERVAL=${task.intervalDays.coerceAtLeast(1)}")
            } else if (task.intervalMonths == 12 && dm != 0) {
                // 每年：月份取锚点日期的月
                val month = task.anchorDate.substring(5, 7).toIntOrNull() ?: 1
                StringBuilder("FREQ=YEARLY;BYMONTH=$month;BYMONTHDAY=$days")
            } else {
                StringBuilder(
                    "FREQ=MONTHLY;INTERVAL=${task.intervalMonths.coerceAtLeast(1)};BYMONTHDAY=$days"
                )
            }
            if (task.endDate.isNotBlank()) {
                sb.append(";UNTIL=${task.endDate.replace("-", "")}T235959Z")
            }
            return sb.toString()
        }
        // RRULE 星期缩写顺序：SU MO TU WE TH FR SA（索引 0=周日）
        val codes = arrayOf("SU", "MO", "TU", "WE", "TH", "FR", "SA")
        val days = ArrayList<String>()
        for (i in 0..6) {
            if ((task.repeatMask and (1 shl i)) != 0) days.add(codes[i])
        }
        return if (days.isEmpty()) "FREQ=DAILY;COUNT=1"
        else "FREQ=WEEKLY;BYDAY=${days.joinToString(",")}"
    }

    private fun findEventId(context: Context, taskId: Long): Long? {
        if (!hasCalendarPermission(context)) return null
        val projection = arrayOf(CalendarContract.Events._ID)
        val selection = "${CalendarContract.Events.CUSTOM_APP_URI} = ?"
        val args = arrayOf("$CALENDAR_TAG://task/$taskId")
        var found: Long? = null
        try {
            context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI, projection, selection, args, null
            )?.use { cur: Cursor ->
                if (cur.moveToFirst()) found = cur.getLong(0)
            }
        } catch (e: Exception) {
            Log.w(TAG, "查询日历事件失败", e)
        }
        return found
    }

    /** 环境自检用：本 App 日历里的日程数。-1 = 无权限，-2 = 日历不存在 / 不可用 */
    fun calendarEventCount(context: Context): Int {
        if (!hasCalendarPermission(context)) return -1
        val calId = ensureCalendar(context) ?: return -2
        return try {
            context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                arrayOf(CalendarContract.Events._ID),
                "${CalendarContract.Events.CALENDAR_ID} = ?",
                arrayOf(calId.toString()), null
            )?.use { it.count } ?: -2
        } catch (e: Exception) {
            Log.w(TAG, "统计日历日程失败", e)
            -2
        }
    }

    /**
     * 任务完成状态同步到日历：完成 → 把这次发生加进 EXDATE（日历当天不再提醒）；
     * 取消完成 → 移除 EXDATE 恢复提醒。没写过日历（未联动）时是空操作。
     *
     * 日历事件的 DTSTART 是「截止前 30 分钟」，所以本次发生的开始时刻
     * 用同一公式倒推，保证 EXDATE 能对上那一次发生。
     */
    fun syncCompletion(context: Context, task: Task, dateKey: String, completed: Boolean): Boolean {
        if (!hasCalendarPermission(context)) return false
        val eventId = findEventId(context, task.id) ?: return false
        val start = millisForKey(dateKey, task.deadlineMin - 30)
        val stamp = exdateStamp(start)
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
        return try {
            val existing = context.contentResolver.query(
                uri, arrayOf(CalendarContract.Events.EXDATE), null, null, null
            )?.use { if (it.moveToFirst()) it.getString(0) else null }
            val set = existing?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
                ?.toMutableSet() ?: mutableSetOf()
            if (completed) set.add(stamp) else set.remove(stamp)
            val values = ContentValues().apply {
                put(CalendarContract.Events.EXDATE, set.joinToString(","))
            }
            context.contentResolver.update(uri, values, null, null) > 0
        } catch (e: Exception) {
            Log.e(TAG, "同步完成状态到日历失败", e)
            false
        }
    }

    /** 发生时刻 → EXDATE 用的时间戳（UTC 绝对时间，带 Z 后缀，避免时区歧义） */
    private fun exdateStamp(millis: Long): String {
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = millis }
        return "%04d%02d%02dT%02d%02d%02dZ".format(
            c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH),
            c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), c.get(Calendar.SECOND)
        )
    }

    /** 从日历中移除某条任务的日程 */
    fun removeFromCalendar(context: Context, taskId: Long): Boolean {
        if (!hasCalendarPermission(context)) return false
        val eventId = findEventId(context, taskId) ?: return true
        return try {
            val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
            context.contentResolver.delete(uri, null, null) > 0
        } catch (e: Exception) {
            Log.e(TAG, "删除日历事件失败", e)
            false
        }
    }

    /** 打开小米日历 */
    fun openCalendar(context: Context): Boolean {
        return try {
            val start = System.currentTimeMillis()
            val uri = ContentUris.withAppendedId(
                android.net.Uri.parse("content://com.android.calendar/time"), start
            )
            val i = Intent(Intent.ACTION_VIEW).setData(uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(i)
            true
        } catch (e: Exception) {
            Log.e(TAG, "打开日历失败", e)
            false
        }
    }
}
