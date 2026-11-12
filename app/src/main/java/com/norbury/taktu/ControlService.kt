package com.norbury.taktu

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.norbury.taktu.util.ControlServer
import com.norbury.taktu.util.Prefs

/**
 * 外部控制的前台服务：让局域网 HTTP server 摆脱"锁屏即被杀"。
 *
 * 生命周期完全跟随「外部控制」开关：
 *   开 → 设置页 startForegroundService 拉起本服务 → 服务内启动 server + 常驻通知
 *   关 → 设置页 stopService + server.stop → 通知消失、端口关闭
 * 开机后若开关是开的，由 BootReceiver 拉起。
 *
 * ponytail: 用 dataSync 前台类型（Android 14+ 要求显式类型）；
 * 通知低优先级、点按进主页面。
 */
class ControlService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 开关已被关掉但服务还残留（如设置页时序差）→ 自行退出
        if (!Prefs.controlEnabled(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 15 对 dataSync 型前台服务限每日累计 6 小时、且禁止开机拉起；
            // 这个服务要长期常驻，Android 14+ 改报 specialUse（v3.34.112）
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            }
            startForeground(NOTIFY_ID, buildNotification(), type)
        } else {
            startForeground(NOTIFY_ID, buildNotification())
        }
        ControlServer.startIfEnabled(this)
        return START_STICKY
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_import)
        .setContentTitle(getString(R.string.control_notif_title))
        .setContentText(
            ControlServer.lanIp()?.let { "http://$it:${ControlServer.PORT}" }
                ?: getString(R.string.control_url_hint)
        )
        .setOngoing(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
        .build()

    override fun onDestroy() {
        // 系统回收/开关关闭都要确保端口不再监听；不清 Prefs（开关状态归设置页管）
        ControlServer.stopServer()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "control_service"
        private const val NOTIFY_ID = 4201

        fun start(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = context.getSystemService(NotificationManager::class.java)
                nm?.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        context.getString(R.string.control_notif_channel),
                        NotificationManager.IMPORTANCE_LOW
                    )
                )
            }
            context.startForegroundService(Intent(context, ControlService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ControlService::class.java))
            ControlServer.stopServer()
        }
    }
}
