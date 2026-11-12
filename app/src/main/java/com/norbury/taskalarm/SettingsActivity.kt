package com.norbury.taskalarm

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.norbury.taskalarm.alarm.RingService
import com.norbury.taskalarm.data.AppDatabase
import com.norbury.taskalarm.data.QuietWindows
import com.norbury.taskalarm.sheet.QuietSheet
import com.norbury.taskalarm.util.ControlServer
import com.norbury.taskalarm.util.HolidayCalendar
import com.norbury.taskalarm.util.MiLinkBridge
import com.norbury.taskalarm.util.Permissions
import com.norbury.taskalarm.util.Prefs
import com.norbury.taskalarm.util.Ui
import java.util.Calendar

/**
 * 环境自检页。
 *
 * 这是本 App 最容易被忽略但最关键的一页：安卓上「闹钟准不准」取决于好几个开关，
 * 缺任何一个都可能出现「提醒晚了十分钟」或「根本不响」。
 * 所以这里把每一项都做成红绿灯 + 一键跳转。
 */
class SettingsActivity : BaseActivity() {

    private val calendarPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            render()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        // 版本号写进可见的顶栏标题。
        // 注意：这个页面用的是自定义顶栏，Activity 的 title 根本不会显示出来
        // （之前写 title = ... 是无效的，用户反馈「设置里找不到版本号」）。
        val vName = packageManager.getPackageInfo(packageName, 0).versionName
        findViewById<TextView>(R.id.tvTitleBar).text =
            "${getString(R.string.settings_title)}  v$vName"

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        setupThemeSection()
        setupRingModeSection()
        setupQuietSection()
        setupControlSection()
    }

    /** 外观：浅色 / 深色 / 跟随系统。桌面小组件的配色也跟这个设置走（不再直接看系统）。 */
    private fun setupThemeSection() {
        val buttons = mapOf(
            Prefs.THEME_LIGHT to findViewById<MaterialButton>(R.id.btnThemeLight),
            Prefs.THEME_DARK to findViewById<MaterialButton>(R.id.btnThemeDark),
            Prefs.THEME_SYSTEM to findViewById<MaterialButton>(R.id.btnThemeSystem)
        )

        fun refresh() {
            val current = Prefs.themeMode(this)
            buttons.forEach { (mode, btn) -> Ui.chip(btn, mode == current) }
        }

        buttons.forEach { (mode, btn) ->
            btn.setOnClickListener {
                Prefs.setThemeMode(this, mode)
                refresh()
                // 小组件配色跟着这个设置走，切换后立即重画（最长要等 30 分钟的定时刷新）
                com.norbury.taskalarm.widget.TodayWidget.refresh(this)
                // setDefaultNightMode 本身会触发全局重建，这里不再手动 recreate，避免闪一下
            }
        }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        render()
        renderNextAlarm()
        refreshRingMode()
        refreshQuietState()
        refreshControlInfo()
    }

    /** 免打扰窗口（考试/会议）：管理入口 + 当前状态，最近的窗口一目了然 */
    private fun setupQuietSection() {
        findViewById<View>(R.id.btnQuietManage).setOnClickListener {
            QuietSheet(this) {
                refreshQuietState()
                // 窗口变了，已排定的闹钟必须按新窗口重排（排定与补响同源）
                com.norbury.taskalarm.alarm.AlarmScheduler.rescheduleAll(this)
            }.show()
        }
        refreshQuietState()
    }

    private fun refreshQuietState() {
        val today = AppDatabase.todayKey()
        val upcoming = QuietWindows.parse(Prefs.quietWindows(this))
            .filter { it.dateKey >= today }
            .sortedWith(compareBy({ it.dateKey }, { it.startMin }))
        findViewById<TextView>(R.id.tvQuietState).text = if (upcoming.isEmpty()) {
            getString(R.string.quiet_state_none)
        } else {
            val first = upcoming.first()
            val dateText = runCatching {
                val inFmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA)
                val date = inFmt.parse(first.dateKey)
                if (date != null)
                    java.text.SimpleDateFormat("M月d日", java.util.Locale.CHINA).format(date)
                else first.dateKey
            }.getOrNull() ?: first.dateKey
            getString(
                R.string.quiet_state_fmt,
                upcoming.size,
                "$dateText ${QuietWindows.fmtMin(first.startMin)} – ${QuietWindows.fmtMin(first.endMin)}"
            )
        }
    }

    /** 外部控制：局域网 HTTP server 开关与对接信息展示 */
    private fun setupControlSection() {
        val sw = findViewById<com.google.android.material.materialswitch.MaterialSwitch>(
            R.id.swControl
        )
        sw.isChecked = Prefs.controlEnabled(this)
        sw.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                Prefs.setControlEnabled(this, true)
                ControlService.start(this)
            } else {
                Prefs.setControlEnabled(this, false)
                ControlService.stop(this)
            }
            refreshControlInfo()
        }

        findViewById<View>(R.id.btnCopyControl).setOnClickListener {
            val ip = ControlServer.lanIp()
            if (ip == null) {
                Toast.makeText(this, getString(R.string.control_no_network), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val info = buildString {
                appendLine("http://$ip:${ControlServer.PORT}")
                appendLine("X-Token: ${Prefs.controlToken(this@SettingsActivity)}")
                append("GET /tasks · POST /import")
            }
            val cm = getSystemService(CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            cm?.setPrimaryClip(android.content.ClipData.newPlainText("TaskAlarm control", info))
            Toast.makeText(this, getString(R.string.control_copied), Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshControlInfo() {
        val running = Prefs.controlEnabled(this)
        findViewById<TextView>(R.id.tvControlState).text =
            getString(if (running) R.string.control_on else R.string.control_off)
        findViewById<TextView>(R.id.tvControlUrl).text =
            ControlServer.lanIp()?.let { "http://$it:${ControlServer.PORT}" }
                ?: getString(R.string.control_url_hint)
        findViewById<TextView>(R.id.tvControlToken).text =
            "X-Token: ${Prefs.controlToken(this)}"
    }

    /** 响铃方式：跟随系统 / 声音+震动 / 仅震动 / 仅声音 */
    private fun setupRingModeSection() {
        val ids = mapOf(
            Prefs.RING_FOLLOW to R.id.chipRingFollow,
            Prefs.RING_SOUND_VIBRATE to R.id.chipRingBoth,
            Prefs.RING_VIBRATE_ONLY to R.id.chipRingVibrate,
            Prefs.RING_SOUND_ONLY to R.id.chipRingSound
        )

        ids.forEach { (mode, id) ->
            findViewById<MaterialButton>(id).setOnClickListener {
                Prefs.setRingMode(this, mode)
                // 通知渠道的声音/震动是创建时写死的，改模式必须重建渠道
                com.norbury.taskalarm.alarm.RingService.applyRingMode(this)
                refreshRingMode()
            }
        }

        findViewById<View>(R.id.btnTestRingMode).setOnClickListener {
            com.norbury.taskalarm.alarm.RingService.start(
                this, -1L, com.norbury.taskalarm.alarm.AlarmReceiver.TYPE_PRE
            )
        }
        refreshRingMode()
    }

    private fun refreshRingMode() {
        val cur = Prefs.ringMode(this)
        mapOf(
            Prefs.RING_FOLLOW to R.id.chipRingFollow,
            Prefs.RING_SOUND_VIBRATE to R.id.chipRingBoth,
            Prefs.RING_VIBRATE_ONLY to R.id.chipRingVibrate,
            Prefs.RING_SOUND_ONLY to R.id.chipRingSound
        ).forEach { (mode, id) -> Ui.chip(findViewById(id), mode == cur) }

        val labelRes = when (cur) {
            Prefs.RING_SOUND_VIBRATE -> R.string.ringmode_state_both
            Prefs.RING_VIBRATE_ONLY -> R.string.ringmode_state_vibrate
            Prefs.RING_SOUND_ONLY -> R.string.ringmode_state_sound
            else -> R.string.ringmode_state_follow
        }
        findViewById<TextView>(R.id.tvRingModeState).text =
            getString(R.string.ringmode_fmt, getString(labelRes))
    }

    /** 显示下一次已排定的提醒 —— 让"闹钟到底排没排上"变成看得见的信息 */
    private fun renderNextAlarm() {
        val at = Prefs.nextAlarmAt(this)
        val tv = findViewById<TextView>(R.id.tvNextAlarm)
        tv.text = if (at <= 0L) {
            getString(R.string.next_alarm_none)
        } else {
            val cal = Calendar.getInstance()
            cal.timeInMillis = at
            val whenText = java.text.SimpleDateFormat("M月d日 HH:mm", java.util.Locale.CHINA)
                .format(cal.time)
            getString(R.string.next_alarm_fmt, whenText, Prefs.nextAlarmLabel(this))
        }

        findViewById<View>(R.id.btnAppSettings).setOnClickListener {
            startActivity(
                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = android.net.Uri.parse("package:$packageName")
                }
            )
        }
    }

    private fun render() {
        val exactOk = Permissions.canScheduleExactAlarms(this)
        val notifOk = Permissions.notificationsEnabled(this)
        // 渠道只在响铃时创建；设置页先把它建好，否则首次安装 / 刚改完响铃方式时
        // 这一行会判红，点进去也没有渠道可设置（用户 2026-11-07 实测）
        RingService.ensureChannel(this)
        // 渠道 id 必须跟 RingService 实际创建的一致：v1 早已不存在，
        // 写死 v1 会让本项永远判红，跳转也打不开任何渠道
        val channelOk = Permissions.channelUsable(this, RingService.CHANNEL_ID)
        val batteryOk = Permissions.ignoringBatteryOptimizations(this)
        val fullScreenOk = Permissions.canUseFullScreenIntent(this)
        val calendarOk = MiLinkBridge.hasCalendarPermission(this)

        bind(
            R.id.rowExact, exactOk,
            getString(R.string.perm_exact_title),
            getString(if (exactOk) R.string.perm_exact_ok else R.string.perm_exact_bad),
            if (exactOk) getString(R.string.perm_manage) else getString(R.string.action_go)
        ) {
            Permissions.exactAlarmIntent(this)?.let { safeStart(it) }
        }

        bind(
            R.id.rowNotif, notifOk,
            getString(R.string.perm_notif_title),
            getString(if (notifOk) R.string.perm_notif_ok else R.string.perm_notif_bad),
            if (notifOk) getString(R.string.perm_manage) else getString(R.string.action_go)
        ) {
            safeStart(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            })
        }

        bind(
            R.id.rowChannel, channelOk,
            getString(R.string.perm_channel_title),
            getString(if (channelOk) R.string.perm_channel_ok else R.string.perm_channel_bad),
            if (channelOk) getString(R.string.perm_manage) else getString(R.string.action_check)
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                safeStart(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                    putExtra(Settings.EXTRA_CHANNEL_ID, RingService.CHANNEL_ID)
                })
            }
        }

        bind(
            R.id.rowFullScreen, fullScreenOk,
            getString(R.string.perm_fullscreen_title),
            getString(if (fullScreenOk) R.string.perm_fullscreen_ok else R.string.perm_fullscreen_bad),
            if (fullScreenOk) getString(R.string.perm_manage) else getString(R.string.action_go)
        ) {
            safeStart(Permissions.fullScreenIntentSettings(this))
        }

        bind(
            R.id.rowBattery, batteryOk,
            getString(R.string.perm_battery_title),
            if (batteryOk) getString(R.string.perm_battery_ok)
            else getString(R.string.perm_battery_bad_fmt, Permissions.romName()),
            if (batteryOk) getString(R.string.perm_manage) else getString(R.string.action_settings)
        ) {
            safeStart(Permissions.batteryOptimizationIntent(this))
        }

        bind(
            R.id.rowCalendar, calendarOk,
            "日历读写权限（小米联动）",
            if (calendarOk) "已开启。可以把任务同步进小米日历"
            else "未开启。开启后才能在编辑任务时同步到系统日历",
            if (calendarOk) getString(R.string.perm_manage) else getString(R.string.action_go)
        ) {
            // 运行时权限：已授予后再 launch 只会秒回 granted，看不到任何东西；
            // 此时想「管理/关闭」只能去应用详情页
            if (calendarOk) {
                safeStart(Permissions.appDetailsIntent(this))
            } else {
                calendarPermLauncher.launch(
                    arrayOf(
                        Manifest.permission.READ_CALENDAR,
                        Manifest.permission.WRITE_CALENDAR
                    )
                )
            }
        }

        // ── 节假日 / 调休 / 时钟识别 ──
        val today = AppDatabase.todayKey()
        val years = HolidayCalendar.coveredYears()
        val diag = StringBuilder()
            .append(getString(R.string.diag_holiday_today_fmt, HolidayCalendar.describe(HolidayCalendar.typeOf(today))))
            .append('\n')
            .append(
                if (HolidayCalendar.isCovered(today)) {
                    getString(R.string.diag_holiday_covered_fmt, years.joinToString("、"))
                } else {
                    getString(R.string.diag_holiday_uncovered)
                }
            )
            .append('\n')
            .append(
                MiLinkBridge.detectClockApp(this)?.let {
                    getString(R.string.diag_clock_ok_fmt, it)
                } ?: getString(R.string.diag_clock_bad)
            )
            .append('\n')
            .append(
                when (val n = MiLinkBridge.calendarEventCount(this)) {
                    -1 -> getString(R.string.diag_cal_no_perm)
                    -2 -> getString(R.string.diag_cal_broken)
                    0 -> getString(R.string.diag_cal_empty)
                    else -> getString(R.string.diag_cal_fmt, n)
                }
            )
        findViewById<TextView>(R.id.tvDiag).text = diag.toString()

        findViewById<TextView>(R.id.tvRom).text =
            "当前机型：${Permissions.romName()}\n" +
                    "如遇闹钟不响，请到系统「设置 → 应用管理 → Taktu」中开启「自启动」，" +
                    "并在最近任务列表里给本应用加锁，防止被清理。"

        findViewById<Button>(R.id.btnTest).setOnClickListener {
            com.norbury.taskalarm.alarm.RingService.start(
                this, -1L, com.norbury.taskalarm.alarm.AlarmReceiver.TYPE_PRE
            )
        }
        findViewById<Button>(R.id.btnReschedule).setOnClickListener {
            com.norbury.taskalarm.alarm.AlarmScheduler.rescheduleAll(this)
            toast("已重新排定所有提醒")
        }
    }

    private fun bind(
        rowId: Int, ok: Boolean, title: String, desc: String, action: String,
        onClick: () -> Unit
    ) {
        val row = findViewById<View>(rowId)
        row.findViewById<TextView>(R.id.tvTitle).text = title
        row.findViewById<TextView>(R.id.tvDesc).text = desc
        row.findViewById<View>(R.id.light)
            .setBackgroundResource(if (ok) R.drawable.dot_ok else R.drawable.dot_bad)

        val act = row.findViewById<TextView>(R.id.tvAction)
        if (action.isEmpty()) {
            act.visibility = View.GONE
        } else {
            act.visibility = View.VISIBLE
            act.text = action
        }
        // 行**永远可点**：未开启 = 「去开启」，已开启 = 「管理」。
        // 之前已开启就把整行 isClickable = false，用户想回系统设置里查看/关闭权限
        // 时点不出任何反应（2026-10-19 反馈）。
        row.isClickable = true
        row.setOnClickListener { onClick() }
    }

    private fun safeStart(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: Exception) {
            // 兜底跳应用详情页。注意不能再走 safeStart 自己——intent 构造正常时
            // 详情页不会失败，但万一连续失败，递归会无限叠栈
            toast("找不到该设置页面，请手动到系统设置中开启")
            try {
                startActivity(Permissions.appDetailsIntent(this))
            } catch (_: Exception) {
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
