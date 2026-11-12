package com.norbury.taskalarm.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 「闹钟和提醒」权限被系统授予或撤销时的回调。
 *
 * 用户在设置里刚打开这个开关的那一刻，之前排定的闹钟其实还是旧的（可能已经退化成
 * 不精确的窗口触发），所以这里收到广播后立刻重排一次，把精度补回来。
 */
class ExactAlarmPermissionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != android.app.AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED) return
        val pending = goAsync()
        try {
            AlarmScheduler.rescheduleAll(context)
        } finally {
            pending.finish()
        }
    }
}
