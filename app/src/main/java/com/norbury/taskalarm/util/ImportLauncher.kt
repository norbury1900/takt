package com.norbury.taskalarm.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.norbury.taskalarm.ImportActivity
import com.norbury.taskalarm.R
import com.norbury.taskalarm.TaskAlarmApp

/**
 * 「把一段任务 JSON 送进导入预览确认流程」的公共入口。
 *
 * 被 AdbImportReceiver（adb 广播）和 ControlServer（局域网 HTTP）共用。
 * 永远不直接写库——确认环节由 ImportActivity 的预览页承担。
 *
 * ## 为什么必须自己判断前台状态
 *
 * Android 10+ 限制后台启动 Activity：App 只有后台服务在跑时，
 * `startActivity` 会被系统**静默拦掉——不抛异常、不给回调**。
 * 所以「try { startActivity } catch { 发通知 }」这种写法在后台时
 * 会两件事都没做成：既没弹预览，也没发通知（静默拦掉不算异常）。
 * 结果就是远程推的任务永远停在「预览已展示」但实际没人看到的状态。
 *
 * 正确做法：自己判断前台
 *  - 前台 → 直接弹预览页
 *  - 后台 → 发一条高优先级通知，用户点按进预览页
 *
 * 两种情况都发通知：即使前台弹窗成功，留一条通知当「任务待确认」的提醒也不坏；
 * 而后台时它是唯一能到达用户的路径。
 */
object ImportLauncher {

    private const val CHANNEL_ID = "adb_import"
    private const val NOTIFY_ID = 4101

    /** 预览最终是怎么送到用户面前的（供服务端如实回报，别再一律说"已展示"） */
    enum class Delivery {
        /** App 在前台，预览页已直接打开 */
        DIRECT,

        /** 已发高优先级通知，等用户点按（后台时的正常路径） */
        NOTIFIED,

        /** 既没弹成也没发成通知（通知权限被关） */
        FAILED
    }

    fun launchPreview(context: Context, json: String): Delivery {
        val preview = Intent(context, ImportActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, json)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        // 通知先发：它是后台场景下唯一可靠的送达路径
        val notified = notifyFallback(context, preview)

        // 前台时直接弹窗（体验最好），失败也不影响通知这条兜底路径
        var direct = false
        if (TaskAlarmApp.isForeground) {
            direct = runCatching {
                context.startActivity(preview)
                true
            }.getOrElse { false }
        }

        // 记下"有预览等着确认"：PC 端据此区分"没送到"和"送到了但没点"
        if (direct || notified) {
            Prefs.setPendingImport(context, json)
        }

        return when {
            direct -> Delivery.DIRECT
            notified -> Delivery.NOTIFIED
            else -> Delivery.FAILED
        }
    }

    /** @return true 表示通知确实发出去了 */
    private fun notifyFallback(context: Context, preview: Intent): Boolean {
        if (!Permissions.notificationsEnabled(context)) return false
        val nm = context.getSystemService(NotificationManager::class.java) ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.adb_import_channel),
                    NotificationManager.IMPORTANCE_HIGH
                )
            )
        }
        return try {
            nm.notify(
                NOTIFY_ID,
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle(context.getString(R.string.adb_import_title))
                    .setContentText(context.getString(R.string.adb_import_body))
                    .setAutoCancel(true)
                    .setContentIntent(
                        PendingIntent.getActivity(
                            context, 0, preview,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )
                    )
                    .build()
            )
            true
        } catch (e: Exception) {
            false
        }
    }
}
