package com.norbury.taktu.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.norbury.taktu.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 桌面组件上「完成」按钮 / 标题点击的入口（v3.34.112）。
 *
 * 单独拆一个 exported=false 的接收器：TodayWidget 本身必须 exported=true
 * 才能在小米桌面的部件列表里枚举到，但打卡是写数据的动作，不该让任意应用
 * 发一个带 task_id 的广播就能改完成状态。
 * PendingIntent 以本应用身份投递，非导出接收器照样能收到。
 */
class WidgetDoneReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TodayWidget.ACTION_TOGGLE_DONE) return
        val taskId = intent.getLongExtra(TodayWidget.EXTRA_TASK_ID, -1L)
        if (taskId <= 0) {
            // 不带 taskId（点标题 / 时间轴 / 空白处）→ 打开 App 主界面
            context.startActivity(
                Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
            )
            return
        }
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                TodayWidget.toggleDone(context, taskId)
                TodayWidget.refresh(context)
            } finally {
                pending.finish()
            }
        }
    }
}
