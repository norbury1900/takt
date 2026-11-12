package com.norbury.taskalarm.alarm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.norbury.taskalarm.util.Prefs

/**
 * 响铃前台服务。这是"真的会响"的那一环：
 *   - 前台服务保证进程不被回收（配合通知常驻）
 *   - MediaPlayer 走 STREAM_ALARM 音量通道，播放系统闹钟铃声并循环
 *   - 同时震动
 *   - 尝试拉起全屏响铃页
 *
 * 声音之所以用「闹钟音量通道」而不是「通知音量通道」：
 * 用户把通知调静音时，闹钟音量通常仍是开着的，这样才真正叫得醒人。
 */
class RingService : Service() {

    companion object {
        const val CHANNEL_ID = "task_alarm_ring_v2"
        const val NOTIF_ID = 1001

        const val EXTRA_TASK_ID = "task_id"
        const val EXTRA_TYPE = "type"

        /** 多时刻任务：本次响的是当天第几个时刻（从 0 起）。响铃页确认时要写进记录 */
        const val EXTRA_TIME_IDX = "time_idx"

        const val ACTION_STOP = "com.norbury.taskalarm.STOP_RING"
        const val ACTION_SNOOZE = "com.norbury.taskalarm.SNOOZE_RING"

        fun start(context: Context, taskId: Long, type: Int, timeIdx: Int = 0) {
            // 登记今日已响（正常响铃 / 补响共用）：去重判断的写入点只在这一处
            Prefs.markFired(
                context, taskId, com.norbury.taskalarm.data.AppDatabase.todayKey(), type, timeIdx
            )
            val i = Intent(context, RingService::class.java).apply {
                putExtra(EXTRA_TASK_ID, taskId)
                putExtra(EXTRA_TYPE, type)
                putExtra(EXTRA_TIME_IDX, timeIdx)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(i)
                } else {
                    context.startService(i)
                }
            } catch (e: Exception) {
                // Android 12+ 后台启动前台服务受限；退化为直接弹全屏页
                Log.e("RingService", "启动前台服务失败，改弹响铃页", e)
                try {
                    context.startActivity(
                        Intent(context, com.norbury.taskalarm.AlarmRingActivity::class.java).apply {
                            addFlags(
                                Intent.FLAG_ACTIVITY_NEW_TASK or
                                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                        Intent.FLAG_ACTIVITY_NO_USER_ACTION
                            )
                            putExtra(EXTRA_TASK_ID, taskId)
                            putExtra(EXTRA_TYPE, type)
                        }
                    )
                } catch (_: Exception) {
                }
            }
        }

        fun stop(context: Context) {
            context.startService(Intent(context, RingService::class.java).apply {
                action = ACTION_STOP
            })
        }

        /**
         * 用户在设置页改了响铃方式：删掉旧通知渠道。
         * 渠道的声音/震动是在创建时写死的，只能重建 —— 下次响铃会按新模式重新创建。
         */
        fun applyRingMode(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            try {
                context.getSystemService(NotificationManager::class.java)
                    ?.deleteNotificationChannel(CHANNEL_ID)
            } catch (e: Exception) {
                Log.e("RingService", "重建通知渠道失败", e)
            }
            // 删完立即按新模式重建：渠道不存在时系统设置点不进「响铃通知渠道」，
            // 环境自检也会误判成「被关闭或降级」（用户 2026-11-07 实测）
            ensureChannel(context)
        }

        /** 建响铃渠道（已存在则不动）。不依赖 Service 实例，设置页改完响铃方式也能调 */
        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            // 老版本用过的 v1 渠道已废弃；留着会在系统设置里多出一条点不动的「Takt 响铃」
            nm.deleteNotificationChannel("task_alarm_ring_v1")
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return

            val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

            val ch = NotificationChannel(
                CHANNEL_ID,
                context.getString(com.norbury.taskalarm.R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(com.norbury.taskalarm.R.string.notif_channel_desc)
                if (ringWantsSound(context)) {
                    setSound(
                        soundUri,
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)                 // ★ 走闹钟音量通道
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                } else {
                    setSound(null, null)
                }
                enableVibration(ringWantsVibrate(context))
                vibrationPattern = longArrayOf(500, 300, 500, 300, 500)
                enableLights(true)
                setBypassDnd(true)                                             // 尝试绕过勿扰
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
            }
            nm.createNotificationChannel(ch)
        }
    }

    /** 当前响铃方式下要不要出声 */
    private fun wantSound(): Boolean = ringWantsSound(this)
    private fun wantVibrate(): Boolean = ringWantsVibrate(this)

    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var ringing = false
    private var ringType = AlarmReceiver.TYPE_PRE

    /** 本次响铃的触发任务。通知里的 PendingIntent 必须把它们带进响铃页，
     *  否则从通知/全屏通知进来时 taskId = -1，页面不知道哪条正在响（用户 2026-10-31 实测） */
    private var curTaskId: Long = -1L
    private var curTimeIdx: Int = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopRing()
                stopSelf()
                return START_NOT_STICKY
            }
        }

        ringType = intent?.getIntExtra(EXTRA_TYPE, AlarmReceiver.TYPE_PRE)
            ?: AlarmReceiver.TYPE_PRE
        curTaskId = intent?.getLongExtra(EXTRA_TASK_ID, -1L) ?: -1L
        curTimeIdx = intent?.getIntExtra(EXTRA_TIME_IDX, 0) ?: 0

        // 默认走响铃流程
        if (!ringing) {
            startForeground(NOTIF_ID, buildNotification("任务到点了", "还没完成，抓紧！", doneLabel()))
            startSound()
            startVibrate()
            launchRingScreen(intent)
            refreshNotificationCount()
            ringing = true
        } else if (curTaskId > 0) {
            // 已经响着时又来一条：让已打开的响铃页切到这条（不然高亮还停在旧任务上）
            launchRingScreen(intent)
        }
        return START_STICKY
    }

    /**
     * 通知里也写清「还有几项没做」—— 锁屏下拉就能看到，不用点开也知道有多严重。
     * 数据库查询必须放后台线程，所以这里异步查完再刷新通知。
     */
    private fun refreshNotificationCount() {
        Thread {
            try {
                val n = com.norbury.taskalarm.util.PendingTasks.now(this).size
                if (n <= 0) return@Thread
                val nm = getSystemService(NotificationManager::class.java) ?: return@Thread
                nm.notify(
                    NOTIF_ID,
                    buildNotification(
                        if (n > 1) "还有 $n 项没完成" else "任务到点了",
                        if (n > 1) "点开逐条处理" else "还没完成，抓紧！",
                        doneLabel()
                    )
                )
            } catch (e: Exception) {
                Log.e("RingService", "统计未完成数量失败", e)
            }
        }.start()
    }

    /**
     * 通知上那个按钮的文案按阶段变：
     *   催促阶段 = 「我知道了」（只是关掉铃声，不标记完成）
     *   复查阶段 = 「已完成」
     */
    private fun doneLabel(): String = getString(
        if (ringType == AlarmReceiver.TYPE_POST) com.norbury.taskalarm.R.string.ring_done
        else com.norbury.taskalarm.R.string.ring_know
    )

    private fun buildNotification(title: String, text: String, doneLabel: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(com.norbury.taskalarm.R.drawable.ic_alarm)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)                // 系统当闹钟处理
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(ringScreenPendingIntent())
            .setFullScreenIntent(ringScreenPendingIntent(), true)          // ★ 锁屏直接弹出
            .addAction(0, doneLabel, stopPendingIntent())
            .addAction(0, getString(com.norbury.taskalarm.R.string.ring_snooze), snoozePendingIntent())
            .build()

    private fun ringScreenPendingIntent(): PendingIntent {
        val i = Intent(this, com.norbury.taskalarm.AlarmRingActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            // 从通知/全屏通知进来也要知道「正在响的是哪条」
            putExtra(EXTRA_TASK_ID, curTaskId)
            putExtra(EXTRA_TYPE, ringType)
            putExtra(EXTRA_TIME_IDX, curTimeIdx)
        }
        return PendingIntent.getActivity(
            this, 10, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun stopPendingIntent(): PendingIntent {
        val i = Intent(this, RingService::class.java).apply {
            action = ACTION_STOP
            data = Uri.parse("taskalarm://stop")
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(
                this, 11, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            PendingIntent.getService(
                this, 11, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }

    private fun snoozePendingIntent(): PendingIntent {
        val i = Intent(this, com.norbury.taskalarm.AlarmRingActivity::class.java).apply {
            action = ACTION_SNOOZE
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            // 必须带上正在响的是哪条：响铃页还没加载清单时，snooze() 只能靠这几个值
            // 决定延后谁；以前不带 → 通知栏「稍后提醒」什么都不排（v3.34.117）
            putExtra(EXTRA_TASK_ID, curTaskId)
            putExtra(EXTRA_TYPE, ringType)
            putExtra(EXTRA_TIME_IDX, curTimeIdx)
        }
        return PendingIntent.getActivity(
            this, 12, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** 播放闹钟铃声，循环直到用户处理 */
    private fun startSound() {
        if (!wantSound()) {
            Log.i("RingService", "按响铃方式设置：本次不出声")
            return
        }
        try {
            val uri: Uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)             // ★ 闹钟音量，静音模式下也能响
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(this@RingService, uri)
                isLooping = true
                prepare()
                start()
            }
        } catch (e: Exception) {
            Log.e("RingService", "播放铃声失败", e)
        }
    }

    private fun startVibrate() {
        if (!wantVibrate()) {
            Log.i("RingService", "按响铃方式设置：本次不震动")
            return
        }
        try {
            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            val pattern = longArrayOf(0, 600, 300, 600, 300, 600, 900)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))   // 0 = 一直循环
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(pattern, 0)
            }
        } catch (e: Exception) {
            Log.e("RingService", "震动失败", e)
        }
    }

    private fun launchRingScreen(intent: Intent?) {
        try {
            startActivity(
                Intent(this, com.norbury.taskalarm.AlarmRingActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                Intent.FLAG_ACTIVITY_NO_USER_ACTION
                    )
                    putExtra(EXTRA_TASK_ID, intent?.getLongExtra(EXTRA_TASK_ID, -1L) ?: -1L)
                    putExtra(EXTRA_TYPE, intent?.getIntExtra(EXTRA_TYPE, AlarmReceiver.TYPE_PRE) ?: 0)
                    putExtra(EXTRA_TIME_IDX, intent?.getIntExtra(EXTRA_TIME_IDX, 0) ?: 0)
                }
            )
        } catch (e: Exception) {
            Log.e("RingService", "拉起响铃页失败（可能被后台启动限制），通知已发出", e)
        }
    }

    private fun stopRing() {
        ringing = false
        try {
            player?.stop()
        } catch (_: Exception) {
        }
        try {
            player?.release()
        } catch (_: Exception) {
        }
        player = null
        try {
            vibrator?.cancel()
        } catch (_: Exception) {
        }
        vibrator = null
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        stopRing()
        super.onDestroy()
    }
}

/** 当前响铃方式下要不要出声（文件级：建渠道时可能没有 Service 实例） */
private fun ringWantsSound(context: Context): Boolean = when (Prefs.ringMode(context)) {
    Prefs.RING_VIBRATE_ONLY -> false
    Prefs.RING_SOUND_ONLY, Prefs.RING_SOUND_VIBRATE -> true
    else -> {   // 跟随系统：只有铃声模式开着才出声
        val am = context.getSystemService(AudioManager::class.java)
        am?.ringerMode == AudioManager.RINGER_MODE_NORMAL
    }
}

/** 当前响铃方式下要不要震动（默认都震 —— 静音时也该叫得醒人） */
private fun ringWantsVibrate(context: Context): Boolean = when (Prefs.ringMode(context)) {
    Prefs.RING_SOUND_ONLY -> false
    Prefs.RING_VIBRATE_ONLY, Prefs.RING_SOUND_VIBRATE -> true
    else -> true
}
