package com.norbury.taktu.sheet

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.content.ContextCompat
import com.google.android.material.R as MaterialR
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.norbury.taktu.R

/**
 * 底部弹窗的公共小工具。
 */
object Sheets {

    /**
     * 把弹窗内容包成毛玻璃（v3.34.89）：
     * BlurView 抓 Activity 根视图实时高斯模糊，上面盖一层半透明玻璃色 + 细描边。
     * 之前弹窗底色直接吃 app_card，深色主题下 app_card 只有 12% 白 —— 看着就是透明的。
     */
    fun frost(activity: Activity, content: View): View {
        val d = activity.resources.displayMetrics.density
        val r = 24 * d
        val blur = eightbitlab.com.blurview.BlurView(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            background = android.graphics.drawable.GradientDrawable().apply {
                // 只圆上面两角，贴住屏幕底边
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
                setColor(ContextCompat.getColor(activity, R.color.glass_tint))
                setStroke(
                    (1 * d).toInt().coerceAtLeast(1),
                    ContextCompat.getColor(activity, R.color.app_stroke)
                )
            }
            clipToOutline = true
            setupWith(
                activity.window.decorView as ViewGroup,
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S)
                    eightbitlab.com.blurview.RenderEffectBlur()
                else
                    eightbitlab.com.blurview.RenderScriptBlur(activity)
            )
                .setFrameClearDrawable(activity.window.decorView.background)
                .setBlurRadius(18f)
                .setBlurAutoUpdate(true)
            addView(content)
        }
        return blur
    }

    /**
     * 强制展开到全高。
     * 不用 [BottomSheetBehavior.from] —— findViewById 返回平台类型，
     * 直接喂进 from 的泛型会被推断成可空类型而报类型不匹配；
     * 改从 layoutParams 里取 behavior，全程显式转换，最稳。
     */
    fun forceExpanded(dialog: BottomSheetDialog) {
        val sheet = dialog.findViewById<View>(MaterialR.id.design_bottom_sheet) ?: return
        val params = sheet.layoutParams as? CoordinatorLayout.LayoutParams ?: return
        val behavior = params.behavior as? BottomSheetBehavior<*> ?: return
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
        behavior.skipCollapsed = true
    }
}
