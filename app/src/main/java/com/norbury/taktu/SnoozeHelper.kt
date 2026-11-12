package com.norbury.taktu

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.norbury.taktu.alarm.AlarmReceiver

/** 「稍后提醒 5 分钟」的实现：排一个一次性闹钟，5 分钟后原样再触发一次 */
object SnoozeHelper {

    private const val SNOOZE_MINUTES = 5
    private const val BASE_CODE = 900000

    fun schedule(context: Context, taskId: Long, type: Int, timeIdx: Int = 0) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerAt = System.currentTimeMillis() + SNOOZE_MINUTES * 60_000L

        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = AlarmReceiver.ACTION_ALARM
            putExtra(AlarmReceiver.EXTRA_TASK_ID, taskId)
            putExtra(AlarmReceiver.EXTRA_TYPE, type)
            // 多时刻任务：延后的还是同一个时刻，不能丢下标（丢了下标会退化成第 0 个时刻）
            putExtra(AlarmReceiver.EXTRA_TIME_INDEX, timeIdx)
            // 重投标记：AlarmReceiver 据此跳过「今日已响」去重
            putExtra(AlarmReceiver.EXTRA_SNOOZE, true)
            data = Uri.parse("taktu://snooze/$taskId/$type/$triggerAt")
        }
        val pi = PendingIntent.getBroadcast(
            context,
            (BASE_CODE + taskId.toInt() * 10 + type),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
        } catch (_: Exception) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
    }
}
