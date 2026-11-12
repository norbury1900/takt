package com.norbury.taskalarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Base64
import android.widget.Toast
import com.norbury.taskalarm.util.ImportLauncher

/**
 * 外部（PC 上的 AI / 脚本）通过 adb 触发导入的入口。
 *
 * PC 端用法（两种等价，二选一）：
 *   adb shell am broadcast -n com.norbury.taktu/.AdbImportReceiver \
 *       -a com.norbury.taskalarm.IMPORT --es b64  <plan.json 的 base64> --es token <X-Token>
 *   adb shell am broadcast -n com.norbury.taktu/.AdbImportReceiver \
 *       -a com.norbury.taskalarm.IMPORT --es json <原始 JSON 文本> --es token <X-Token>
 *
 * base64 形式推荐：无 shell 转义问题。收到后**不直接写库**——
 * 经 [ImportLauncher] 转发进 ImportActivity 的"解析 → 预览 → 勾选 → 确认"流程。
 */
class AdbImportReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // 广播是应用间入口：必须带 token，否则任意已安装应用都能往用户面前塞导入预览（v3.34.118）。
        // 校验失败静默丢弃，不给调用方任何反馈。
        val token = intent.getStringExtra(EXTRA_TOKEN)
        if (token.isNullOrBlank() || token != com.norbury.taskalarm.util.Prefs.controlToken(context)) return

        val json = intent.getStringExtra(EXTRA_B64)?.let {
            runCatching { String(Base64.decode(it, Base64.DEFAULT)) }.getOrNull()
        } ?: intent.getStringExtra(EXTRA_JSON)

        if (json.isNullOrBlank()) {
            Toast.makeText(context, R.string.import_empty, Toast.LENGTH_SHORT).show()
            return
        }
        ImportLauncher.launchPreview(context, json)
    }

    companion object {
        const val ACTION_IMPORT = "com.norbury.taskalarm.IMPORT"
        const val EXTRA_JSON = "json"
        const val EXTRA_B64 = "b64"

        /** 与小号/大号无关的应用内控制 token（设置页「外部控制」里那串） */
        const val EXTRA_TOKEN = "token"
    }
}
