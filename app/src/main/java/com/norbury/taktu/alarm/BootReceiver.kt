package com.norbury.taktu.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.norbury.taktu.ControlService
import com.norbury.taktu.util.Prefs

/** 开机 / 应用更新后重新排定所有闹钟（系统重启会清空 AlarmManager） */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON" -> {
                AlarmScheduler.rescheduleAll(context)
                // 外部控制若开着，开机后恢复前台服务（BOOT_COMPLETED 允许拉起 FGS）
                if (Prefs.controlEnabled(context)) {
                    ControlService.start(context)
                }
            }
        }
    }
}
