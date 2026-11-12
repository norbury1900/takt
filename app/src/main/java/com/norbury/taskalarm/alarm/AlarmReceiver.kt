package com.norbury.taskalarm.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.norbury.taskalarm.data.AppDatabase
import com.norbury.taskalarm.data.RepeatRule
import com.norbury.taskalarm.util.Prefs

/**
 * 闹钟触发入口。
 *
 * 这里是「条件响铃」的判断点：闹钟到点后**不是直接响**，
 * 而是先查数据库确认这条任务今天到底做了没有 —— 做了就静默跳过，没做才拉起响铃服务。
 * 这正是用户要的"我做了就不响，没做才响"。
 *
 * 注意：BroadcastReceiver 的 onReceive 只有约 10 秒，且不能做网络/长耗时操作，
 * 所以这里只做一次轻量数据库查询，然后立刻交给前台服务。
 */
class AlarmReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_ALARM = "com.norbury.taskalarm.ACTION_ALARM"
        const val EXTRA_TASK_ID = "task_id"
        const val EXTRA_TYPE = "type"

        /** 排定时计划触发的绝对时刻（毫秒）。仅用于日志，判定以查库结果为准 */
        const val EXTRA_TRIGGER_AT = "trigger_at"

        /** 多时刻任务：本次响的是当天第几个时刻（从 0 起） */
        const val EXTRA_TIME_INDEX = "time_index"

        /** 稍后提醒的重投：跳过「今日已响」去重，否则延后那一次会被第一次响铃挡掉（v3.34.112） */
        const val EXTRA_SNOOZE = "snooze"

        private const val TAG = "AlarmReceiver"
        const val TYPE_PRE = 0
        const val TYPE_POST = 1
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_ALARM) return
        val taskId = intent.getLongExtra(EXTRA_TASK_ID, -1L)
        val type = intent.getIntExtra(EXTRA_TYPE, TYPE_PRE)
        val timeIdx = intent.getIntExtra(EXTRA_TIME_INDEX, 0)
        if (taskId <= 0) return

        Log.i(TAG, "闹钟触发 taskId=$taskId type=$type timeIdx=$timeIdx")

        // 用 goAsync 争取一点时间做数据库查询（轻量查询足够）
        val pending = goAsync()
        Thread {
            try {
                val dao = AppDatabase.get(context).taskDao()
                val task = dao.getByIdSync(taskId)

                if (task == null || !task.enabled) {
                    Log.i(TAG, "任务不存在或已停用，跳过")
                    return@Thread
                }

                // 今天不是该提醒的日子（已考虑法定节假日与调休），跳过
                val todayDow = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_WEEK)
                val dateKey = AppDatabase.todayKey()
                if (!RepeatRule.shouldTriggerTask(task, dateKey, todayDow)) {
                    Log.i(TAG, "今天不是提醒日（$dateKey 属 ${com.norbury.taskalarm.util.HolidayCalendar.typeOf(dateKey)}），跳过")
                    return@Thread
                }

                // ★ 核心判断：已完成则静默跳过，不做任何打扰
                // v3.34.21：走 Task.allTimesConfirmed —— 多时刻任务只完成了一个时刻时，
                // 剩下那些时刻仍然要响；只有全部时刻都确认了才整天静默。
                val record = dao.getRecordSync(dateKey, taskId)
                if (task.allTimesConfirmed(record)) {
                    Log.i(TAG, "「${task.name}」今日已完成，不响铃")
                    return@Thread
                }

                // 多时刻任务逐时刻确认：本时刻已确认就不再响（2026-10-20 用户定案）
                if (task.timeSpec.isNotBlank() && task.timeConfirmed(record, timeIdx)) {
                    Log.i(TAG, "「${task.name}」第 ${timeIdx + 1} 个时刻已确认，不响铃")
                    return@Thread
                }

                // 同一天同一 (提醒类型, 时刻) 只拉起一次：正常响铃 / 补响 / 重复投递去重。
                // 副作用：响过之后当天再改这条任务的时间不会二次响——宁可少响别多响。
                // 稍后提醒是用户主动要求的重投，必须绕过这一层，否则 5 分钟后必被自己挡掉。
                val snoozed = intent.getBooleanExtra(EXTRA_SNOOZE, false)
                if (!snoozed && Prefs.wasFired(context, taskId, dateKey, type, timeIdx)) {
                    Log.i(TAG, "「${task.name}」该提醒今天已响过，跳过重复投递")
                    return@Thread
                }

                Log.i(TAG, "「${task.name}」未完成，开始响铃")
                RingService.start(context, taskId, type, timeIdx)

                // 触发过后补排后续几天的闹钟，保证长期有效（走调度串行队列）
                AlarmScheduler.scheduleLater(context, task)

            } catch (e: Exception) {
                Log.e(TAG, "处理闹钟失败", e)
            } finally {
                pending.finish()
            }
        }.start()
    }
}
