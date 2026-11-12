package com.norbury.taktu

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.core.content.ContextCompat
import com.norbury.taktu.data.AppDatabase
import com.norbury.taktu.data.AutoPurge
import com.norbury.taktu.util.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 应用入口。
 *
 * 1. 恢复深浅模式选择（跟随系统 / 浅色 / 深色）
 * 2. 主题色板与动态取色统一由 [BaseActivity] 在每个页面创建时应用：
 *    这里不再注册全局 DynamicColors，否则壁纸色会盖过用户选定的色板。
 * 3. 记录「当前有没有可见的界面」——后台启动 Activity 在 Android 10+ 受限，
 *    远程推送任务时需要据此决定走弹窗还是走通知（见 ImportLauncher）。
 * 4. 「跟随系统」模式下系统切换深浅色时立即重画桌面小组件（v3.34.51）——
 *    组件自身的定时刷新最长 30 分钟，不接住这个广播就会一直穿旧配色。
 */
class TaktuApp : Application() {

    companion object {
        /**
         * 可见 Activity 的数量。>0 表示 App 在前台，=0 表示只有后台服务在跑。
         *
         * 写在 Application 的 companion 上，ControlServer（跑在同进程的服务里）能直接读到。
         */
        @Volatile
        var visibleActivities: Int = 0
            private set

        /** App 是否在前台（至少有一个可见界面） */
        val isForeground: Boolean get() = visibleActivities > 0

        /**
         * 跨天清零任务表里的组数进度（v3.34.112）。
         * Task.doneCount 只是「今天」进度的缓存，真正口径是当天的 TaskRecord；
         * 以前从不清零，昨天做满的任务今天一打开就显示满额，组件里点一次还会直接记成完成。
         * 进程启动与主页 onResume 各调一次，App 常驻跨夜也能在回到前台时纠正。
         */
        fun resetDailyDone(context: Context) {
            val key = AppDatabase.todayKey()
            if (Prefs.doneResetKey(context) == key) return
            Prefs.setDoneResetKey(context, key)
            CoroutineScope(Dispatchers.IO).launch {
                runCatching { AppDatabase.get(context).taskDao().resetDoneCounts() }
            }
        }
    }

    /**
     * 过期数据自动清理（v3.34.104）：
     * 删除「过期超过 30 天」的任务及其完成记录，再清掉孤儿记录。
     * 后台线程执行，失败静默（下次启动会重试，不影响主流程）。
     */
    private fun purgeExpiredData() {
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                val dao = AppDatabase.get(this@TaktuApp).taskDao()
                val ids = AutoPurge.expiredTaskIds(
                    dao.getAll(), AppDatabase.keyDaysAgo(AutoPurge.KEEP_DAYS)
                )
                if (ids.isNotEmpty()) {
                    dao.deleteRecordsByTaskIds(ids)
                    dao.deleteByIds(ids)
                }
                dao.deleteOrphanRecords()
                Prefs.markPurged(this@TaktuApp)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Prefs.applyTheme(Prefs.themeMode(this))

        // 跨天清零组数进度（v3.34.112）：进程起来先纠正一次
        resetDailyDone(this)

        registerActivityLifecycleCallbacks(VisibleCounter())

        // 系统深浅色切换 → 组件重画。App 内切主题在 SettingsActivity 里直接调 refresh，
        // 这里只兜「跟随系统」模式下的系统级切换。
        ContextCompat.registerReceiver(
            this,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    com.norbury.taktu.widget.TodayWidget.refresh(context)
                }
            },
            IntentFilter(Intent.ACTION_CONFIGURATION_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        // 组件自刷新节拍（v3.34.97）：重启后系统会清掉闹钟，进程每次起来补一次注册
        runCatching { com.norbury.taktu.widget.TodayWidget.scheduleTick(this) }

        // 过期数据自动清理（v3.34.104）：每周一次，进程启动时到点就清，不需要用户操作
        if (Prefs.needsPurge(this)) purgeExpiredData()

        // 外部控制开关若已打开，进程起来就拉起前台服务（服务内再启 HTTP server）
        if (Prefs.controlEnabled(this)) {
            com.norbury.taktu.ControlService.start(this)
        }
    }

    /** 统计可见 Activity 数：onStart +1 / onStop -1 */
    private class VisibleCounter : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
            visibleActivities++
        }

        override fun onActivityStopped(activity: Activity) {
            if (visibleActivities > 0) visibleActivities--
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityResumed(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }
}
