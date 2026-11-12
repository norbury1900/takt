package com.norbury.taskalarm.util

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * 权限与环境检查。App 的「环境自检」页依赖这个类。
 * 安卓上闹钟要真正响，这几个开关缺一不可，所以每一项都要能一键跳转去开。
 */
object Permissions {

    /** 精确闹钟权限（Android 12+；14+ 默认拒绝） */
    fun canScheduleExactAlarms(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return am.canScheduleExactAlarms()
    }

    /** 跳转到「闹钟和提醒」特殊权限页 */
    fun exactAlarmIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /** 通知是否已开启（Android 13+ 的 POST_NOTIFICATIONS） */
    fun notificationsEnabled(context: Context): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        }
        return true
    }

    /** 响铃通道是否被用户降级或关闭 */
    fun channelUsable(context: Context, channelId: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        val nm = context.getSystemService(NotificationManager::class.java) ?: return false
        val ch = nm.getNotificationChannel(channelId) ?: return false
        return ch.importance != NotificationManager.IMPORTANCE_NONE
    }

    /** 是否已加入电池优化白名单（false 表示被优化，可能被延迟） */
    fun ignoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** 跳转申请忽略电池优化 */
    fun batteryOptimizationIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    /** 全屏通知权限（Android 14+ 需用户单独授予） */
    fun canUseFullScreenIntent(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
        val nm = context.getSystemService(NotificationManager::class.java) ?: return false
        return nm.canUseFullScreenIntent()
    }

    fun fullScreenIntentSettings(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    /** 应用详情页，兜底用 */
    fun appDetailsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    /** 识别厂商，用于保活引导 */
    fun romName(): String {
        val brand = "${Build.BRAND} ${Build.MANUFACTURER}".lowercase()
        return when {
            brand.contains("xiaomi") || brand.contains("redmi") || brand.contains("poco") -> "小米 / Redmi"
            brand.contains("huawei") || brand.contains("honor") -> "华为 / 荣耀"
            brand.contains("oppo") || brand.contains("realme") || brand.contains("oneplus") -> "OPPO / 一加 / realme"
            brand.contains("vivo") || brand.contains("iqoo") -> "vivo / iQOO"
            brand.contains("meizu") -> "魅族"
            brand.contains("samsung") -> "三星"
            else -> "其他"
        }
    }
}
