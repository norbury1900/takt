package com.norbury.taktu.util

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.norbury.taktu.R
import kotlin.math.roundToInt

/**
 * 组件外观的统一入口。
 *
 * 颜色一律取 @color 资源（values / values-night 两套），
 * 这样深浅两套主题自动生效，代码里不出现任何硬编码色值。
 */
object Ui {

    /** 状态药丸的四种语义 */
    enum class Pill { NEUTRAL, DONE, URGING, OVERDUE }

    /**
     * 选项芯片：独立胶囊（参考图里的 Whole / Half / Quarter）。
     * 选中 = 陶土橘实心 + 白字；未选中 = 透明底 + 细描边 + 次要字色。
     */
    fun chip(btn: MaterialButton, selected: Boolean, radiusDp: Int = 23) {
        val d = btn.resources.displayMetrics.density
        btn.cornerRadius = (radiusDp * d).roundToInt()
        btn.strokeWidth = (1 * d).roundToInt()

        if (selected) {
            val accent = ContextCompat.getColor(btn.context, R.color.accent)
            btn.backgroundTintList = android.content.res.ColorStateList.valueOf(accent)
            btn.setTextColor(ContextCompat.getColor(btn.context, R.color.accent_on))
            btn.setStrokeColorResource(R.color.accent)
        } else {
            // 未选中：纯文字态，不再描边 —— 描边胶囊成排堆在一起是"模板感"的另一来源
            btn.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.TRANSPARENT)
            btn.setTextColor(ContextCompat.getColor(btn.context, R.color.app_text_sub))
            btn.strokeWidth = 0
        }
    }

    /**
     * 底部悬浮导航项（v3.34.74：选中 = 红实心圆 + 白图标，用户定稿）：
     * 纯图标，44dp 圆位，未选中 = 透明底 + 暖灰图标。
     * 圆底画在 44dp 的 ImageView 上，外层是 weight 平分的点击热区（全宽可点）。
     */
    fun navTab(item: View, selected: Boolean) {
        if (item !is ViewGroup) return
        var icon: ImageView? = null
        for (i in 0 until item.childCount) {
            val c = item.getChildAt(i)
            if (c is ImageView) {
                icon = c
                break
            }
        }
        icon ?: return
        icon.background = if (selected) {
            ContextCompat.getDrawable(item.context, R.drawable.bg_circle_accent)
        } else {
            null
        }
        icon.imageTintList = ColorStateList.valueOf(
            ContextCompat.getColor(
                item.context,
                if (selected) R.color.accent_on else R.color.app_text_sub
            )
        )
    }

    /** 状态药丸：胶囊底 + 对应的 on-container 文字色 */
    fun pill(tv: TextView, kind: Pill) {
        val (bgRes, fgRes) = when (kind) {
            Pill.DONE -> R.color.ok_container to R.color.ok_on_container
            Pill.URGING -> R.color.accent_container to R.color.accent_on_container
            Pill.OVERDUE -> R.color.danger_container to R.color.danger_on_container
            Pill.NEUTRAL -> R.color.wait_container to R.color.wait_on_container
        }
        val ctx = tv.context
        tv.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 999f
            setColor(ContextCompat.getColor(ctx, bgRes))
        }
        tv.setTextColor(ContextCompat.getColor(ctx, fgRes))
    }
}
