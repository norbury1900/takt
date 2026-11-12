package com.norbury.taktu.sheet

import android.app.Activity
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.norbury.taktu.R
import android.view.ContextThemeWrapper

/**
 * 确认弹窗（底部弹窗版，替代系统 AlertDialog —— 那个白底灰钮与全站暖白/陶土橘主题打架）。
 *
 * 与全站弹窗同视觉：标题 + 说明 + 右下角"文字钮（取消）+ 实底钮（确认）"，
 * 按钮样式走主题 overlay（ThemeOverlay.Taktu.SheetButtonText / 默认实底）。
 * 两个按钮点击后都会关闭弹窗；确认回调里做实际动作，取消回调可选。
 *
 * 用例：删除任务确认、闹钟权限引导（TaskEditActivity）。
 */
class ConfirmSheet(
    activity: Activity,
    title: String,
    message: String? = null,
    confirmText: String,
    cancelText: String = "取消",
    private val onConfirm: () -> Unit,
    private val onCancel: (() -> Unit)? = null
) : BottomSheetDialog(activity) {

    init {
        val d = activity.resources.displayMetrics.density
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * d).toInt(), (18 * d).toInt(), (20 * d).toInt(), (14 * d).toInt())
        }

        root.addView(TextView(activity).apply {
            text = title
            textSize = 19f
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(activity, R.color.app_text))
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = (10 * d).toInt() })

        if (!message.isNullOrBlank()) {
            root.addView(TextView(activity).apply {
                text = message
                textSize = 13.5f
                setLineSpacing(0f, 1.4f)
                setTextColor(ContextCompat.getColor(activity, R.color.app_text_sub))
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (14 * d).toInt() })
        }

        val btnCancel = MaterialButton(
            android.view.ContextThemeWrapper(activity, R.style.ThemeOverlay_Taktu_SheetButtonText)
        ).apply {
            text = cancelText
            setTextColor(ContextCompat.getColor(activity, R.color.app_text_sub))
        }
        val btnConfirm = MaterialButton(activity).apply {
            text = confirmText
        }
        btnCancel.setOnClickListener { dismiss(); onCancel?.invoke() }
        btnConfirm.setOnClickListener { dismiss(); onConfirm() }

        root.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            addView(btnCancel)
            addView(btnConfirm, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = (8 * d).toInt() })
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        setContentView(root)
    }
}
