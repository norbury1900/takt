package com.norbury.taskalarm

import android.os.Build
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.norbury.taskalarm.util.ButtonTuner
import com.norbury.taskalarm.util.Prefs

/**
 * 所有页面的基类：统一记录「创建时用的是哪套外观」。
 *
 * 切到深色/浅色后，返回栈里已存在的 Activity 不会自动重建，
 * 因此 onResume 发现外观变了就 recreate，避免首页还停留在旧主题。
 */
abstract class BaseActivity : AppCompatActivity() {

    /**
     * 打卡 / 确认类操作的统一触感。放基类是为了口径一致：勾清单、记组数、
     * 确认时刻各页都走同一强度；振动挂在 window.decorView 上 ——
     * 打卡回调里往往没有现成的 View 接收者。
     *
     * 强度：API 30+ 用 CONFIRM（系统为「确认成功」设计的强双击感），
     * 低版本回落 LONG_PRESS —— 两者都比最初的 VIRTUAL_KEY 明显得多
     * （v3.34.61 用户实测轻点几乎感觉不出来）。
     */
    protected fun tickHaptic() {
        val view = window?.decorView ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        } else {
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
    }

    private var appliedTheme = Int.MIN_VALUE

    override fun onCreate(savedInstanceState: Bundle?) {
        appliedTheme = Prefs.themeMode(this)
        super.onCreate(savedInstanceState)
    }

    /**
     * 所有页面 setContentView 之后统一做一遍按钮文字居中矫正。
     * RecyclerView 里的按钮（任务卡片、响铃列表项）由各自 Adapter 单独调，
     * 因为它们在页面 setContentView 之后才创建。
     */
    override fun setContentView(view: View?) {
        super.setContentView(view)
        ButtonTuner.tune(view)
    }

    override fun setContentView(layoutResID: Int) {
        super.setContentView(layoutResID)
        ButtonTuner.tune(findViewById(android.R.id.content))
    }

    override fun onResume() {
        super.onResume()
        val now = Prefs.themeMode(this)
        if (appliedTheme != now) {
            appliedTheme = now
            recreate()
        }
    }
}
