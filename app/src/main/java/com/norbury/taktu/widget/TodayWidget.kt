package com.norbury.taktu.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import com.norbury.taktu.MainActivity
import com.norbury.taktu.data.CheckIn
import com.norbury.taktu.R
import com.norbury.taktu.data.AppDatabase
import com.norbury.taktu.data.RepeatRule
import com.norbury.taktu.data.TimelineModel
import com.norbury.taktu.data.buildTimeline
import com.norbury.taktu.data.layout
import com.norbury.taktu.util.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 桌面小部件：「迷你时间轴」（v3.34.42，Demo_3 落地）。
 * 竖脊线串起今天各时刻任务（已完成的灰化），陶土色「现在」线横穿，
 * 右上角报「还剩 N 件」，底部胶囊一键完成下一件。
 *
 * 渲染：RemoteViews 白名单装不下自定义 View，时间轴整幅画进位图挂 ImageView
 * （见 [TimelineRenderer]）；数据/布局算法在 data/WidgetTimeline（有单测）。
 *
 * 数据变了不靠监听，靠：App 内列表刷新主动调 [refresh] / 打卡广播自刷新 /
 * updatePeriodMillis 每 30 分钟定时 onUpdate / 桌面调整尺寸时按新尺寸重画。
 */
class TodayWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        scheduleTick(context)
        refresh(context)
    }

    /** 桌面放上组件时启动自刷新（v3.34.97） */
    override fun onEnabled(context: Context) {
        scheduleTick(context)
    }

    /** 最后一个组件移除时停掉闹钟，别空转 */
    override fun onDisabled(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        am.cancel(tickPendingIntent(context))
    }

    /** 拖拽改尺寸后按新尺寸重画位图（高度变化会重新布置行距） */
    override fun onAppWidgetOptionsChanged(
        context: Context, manager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle?
    ) {
        refresh(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        // 自刷新节拍（v3.34.97）：updatePeriodMillis 最快也只有 30 分钟，
        // 小米还会再压 —— 早上刷的图会一直挂到中午，「现在」线就停在昨晚的位置
        if (intent.action == ACTION_TICK) {
            refresh(context)
            return
        }
        // 打卡 / 打开 App 走 WidgetDoneReceiver（exported=false）：
        // 本 provider 必须 exported=true 才能在小米桌面的部件列表里出现（v3.34.112）
        super.onReceive(context, intent)
    }

    companion object {

        /** 行尾「完成」按钮的打卡广播；不带 EXTRA_TASK_ID 的同 action 打开主界面 */
        const val ACTION_TOGGLE_DONE = "com.norbury.taktu.action.WIDGET_TOGGLE_DONE"
        const val EXTRA_TASK_ID = "task_id"

        /** 自刷新节拍：10 分钟一次，「现在」线最多滞后 10 分钟 */
        private const val ACTION_TICK = "com.norbury.taktu.action.WIDGET_TICK"
        private const val TICK_MS = 10 * 60 * 1000L

        private fun tickPendingIntent(context: Context): PendingIntent =
            PendingIntent.getBroadcast(
                context, 1,
                Intent(context, TodayWidget::class.java).setAction(ACTION_TICK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

        /** 每 10 分钟叫醒自己重画一次（inexact 即可，不需要精确闹钟权限） */
        fun scheduleTick(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            am.setInexactRepeating(
                android.app.AlarmManager.RTC,
                System.currentTimeMillis() + TICK_MS,
                TICK_MS,
                tickPendingIntent(context)
            )
        }

        /** 任何任务 / 打卡变化后调用；无小部件在桌面上时是空操作。
         *  每步留痕进 Prefs（/widget_debug 读取）——MIUI 只给一句「载入出现问题」，
         *  崩在哪一步 App 侧必须自己记。 */
        fun refresh(context: Context) {
            CoroutineScope(Dispatchers.Default).launch {
                val trace = StringBuilder()
                try {
                    val manager = AppWidgetManager.getInstance(context)
                    val ids = manager.getAppWidgetIds(ComponentName(context, TodayWidget::class.java))
                    trace.append("ids=").append(ids.size)
                    if (ids.isEmpty()) {
                        Prefs.setWidgetDebug(context, "OK ${Prefs.nowStamp()} 无组件在桌面")
                        return@launch
                    }

                    val model = todayTimeline(context)
                    trace.append(" entries=").append(model.entries.size)
                    val next = model.nextUndone

                    // 配色跟随 App 的「外观」设置（v3.34.51）：浅色→亮、深色→暗、
                    // 跟随系统才看系统 uiMode。之前直接读系统配置，App 浅色 + 系统深色
                    // 时组件与 App 打架、颜色来回切（用户 2026-10-23 实测）。
                    val mode = Prefs.themeMode(context)
                    val appCtx = context.applicationContext
                    val dark = when (mode) {
                        Prefs.THEME_DARK -> true
                        Prefs.THEME_SYSTEM -> appCtx.resources.configuration.uiMode and
                            android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
                            android.content.res.Configuration.UI_MODE_NIGHT_YES
                        else -> false
                    }
                    trace.append(" mode=").append(mode).append(" dark=").append(dark)

                    ids.forEach { id ->
                        // 竖向高度以 MIN_HEIGHT 为准；拖拽改尺寸走 onAppWidgetOptionsChanged 重画
                        val opts = manager.getAppWidgetOptions(id)
                        val wDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 180)
                            .coerceAtLeast(100).toFloat()
                        val hDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 110)
                            .coerceAtLeast(100).toFloat()

                        val rv = RemoteViews(context.packageName, R.layout.widget_today).apply {
                            setInt(R.id.widgetRoot, "setBackgroundResource",
                                if (dark) R.drawable.bg_widget_dark else R.drawable.bg_widget)
                            setOnClickPendingIntent(R.id.widgetTimeline, openAppPendingIntent(context))

                            // 表头右侧：当前时间 · 还剩 N 件（整卡进位图，v3.34.45）
                            val nowCal = java.util.Calendar.getInstance()
                            val nowText = com.norbury.taktu.data.Task.formatMin(
                                nowCal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + nowCal.get(java.util.Calendar.MINUTE)
                            )
                            val headerRight = context.getString(
                                R.string.widget_remaining_fmt, model.total - model.doneCount, nowText
                            )
                            // 空态文案：全做完 → 今日事毕；本来就没有任务 → 好好休息
                            val emptyText =
                                if (model.entries.isEmpty())
                                    context.getString(
                                        if (model.total > 0) R.string.widget_empty_done else R.string.widget_empty
                                    )
                                else null
                            val pillText = next?.let { context.getString(R.string.widget_done_fmt, it.name) }

                            setImageViewBitmap(
                                R.id.widgetTimeline,
                                TimelineRenderer.render(
                                    context, model, wDp - 0f, hDp - 0f, dark,
                                    headerRight, emptyText, pillText
                                )
                            )
                            if (pillText != null) {
                                // 透明点击层与位图里的胶囊对齐（固定右下角 132x30dp）
                                setOnClickPendingIntent(R.id.btnWidgetDone, togglePendingIntent(context, next.taskId))
                                setViewVisibility(R.id.btnWidgetDone, View.VISIBLE)
                            } else {
                                setViewVisibility(R.id.btnWidgetDone, View.GONE)
                            }
                        }
                        manager.updateAppWidget(id, rv)
                    }
                    trace.append(" updated")
                    Prefs.setWidgetDebug(context, "OK ${Prefs.nowStamp()} $trace")
                } catch (e: Throwable) {
                    val at = e.stackTrace.take(3).joinToString("<-") { it.className.substringAfterLast('.') + "." + it.methodName }
                    Prefs.setWidgetDebug(
                        context,
                        "ERROR ${Prefs.nowStamp()} $trace ${e.javaClass.simpleName}: ${e.message} @ $at"
                    )
                }
            }
        }

        /** 打卡广播：requestCode 用 taskId 区分（intent 相同必须靠 code 分流） */
        private fun togglePendingIntent(context: Context, taskId: Long): PendingIntent =
            PendingIntent.getBroadcast(
                context, taskId.toInt(),
                Intent(context, WidgetDoneReceiver::class.java)
                    .setAction(ACTION_TOGGLE_DONE)
                    .putExtra(EXTRA_TASK_ID, taskId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

        /** 标题 / 时间轴点击 → 打开主界面（无 taskId，接收器里走打开分支） */
        private fun openAppPendingIntent(context: Context): PendingIntent =
            PendingIntent.getBroadcast(
                context, 0,
                Intent(context, WidgetDoneReceiver::class.java).setAction(ACTION_TOGGLE_DONE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

        /** 今日全部时刻（含已完成）构建时间轴模型：过滤口径与 App 内一致 */
        private fun todayTimeline(context: Context): TimelineModel {
            val dao = AppDatabase.get(context).taskDao()
            val today = AppDatabase.todayKey()
            val dow = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_WEEK)
            val tasks = dao.getEnabledSync()
                .filter { RepeatRule.shouldTriggerTask(it, today, dow) }
            val records = tasks.associate { it.id to dao.getRecordSync(today, it.id) }
            val cal = java.util.Calendar.getInstance()
            val nowMin = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
            return buildTimeline(tasks, records, today, nowMin)
        }

        /** 打卡语义与主界面一致：普通任务直接完成，组数任务 +1 组、满目标自动完成 */
        internal fun toggleDone(context: Context, taskId: Long) {
            val dao = AppDatabase.get(context).taskDao()
            val t = dao.getByIdSync(taskId) ?: return
            val today = AppDatabase.todayKey()
            if (t.targetCount > 0) {
                val step = com.norbury.taktu.stepOf(t)
                // 基准取当天记录：Task.doneCount 跨天可能是昨天的满额（v3.34.112）
                CheckIn.update(context, dao, t) { base ->
                    val done = (base.doneCount + step).coerceIn(0, t.targetCount)
                    val full = done >= t.targetCount
                    base.copy(doneCount = done, completed = if (t.isMultiTime) base.completed else full)
                }
            } else if (t.isMultiTime) {
                // 逐时刻：确认最早没确认的那个（全部确认过了就整体退回）；
                // 择一提醒的「确认一个即整条完成」由 CheckIn.resolve 统一处理（v3.34.114）
                CheckIn.update(context, dao, t) { base ->
                    val idx = t.earliestUnconfirmed(base)
                    if (idx < 0) base.clearConfirmedTimes()
                    else base.confirmTime(idx, t.timesOn(today).size)
                }
            } else {
                CheckIn.update(context, dao, t) { it.copy(completed = true, doneCount = 0) }
            }
        }
    }
}
