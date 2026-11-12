package com.norbury.taktu.util

import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.button.MaterialButton
import com.norbury.taktu.R

/**
 * 按钮与输入框的视觉矫正（字体 / 垂直居中）。
 *
 * 背景一（居中）：全局字体是朱雀仿宋这类衬线中文字体，ascent 大、descent 小，
 * Android 会按 ascent→descent 排文字块，再叠加 firstBaselineToTopHeight
 * （Material 按钮默认带值）后，只写 gravity=center 文字仍会偏上。
 *
 * 背景二（字体）：M3 按钮样式把 android:textAppearance 硬编码为 LabelLarge（非 ?attr），
 * 普通 TextView 也可能只写 textSize 不挂 textAppearance —— 这两类会绕过主题的字体覆写。
 * 静态兜底写在 themes.xml（Widget.Taktu.Button / .TextView / textViewStyle），
 * EditText 的 defStyleAttr 是 editTextStyle，覆写它要猜 M3 父样式名，不如运行时直接设。
 *
 * 做法（两条都做，缺一不可）：
 *  1. 静态度量在 themes.xml 写 gravity=center + includeFontPadding=false；
 *  2. firstBaseline/lastBaselineToBottomHeight 是 API 28 属性，写 XML 会撞
 *     minSdk 26 的 lint（NewApi），所以运行时判断后强制归零。
 */
object ButtonTuner {

    /** 从 Activity 的根视图或 RecyclerView 的 itemView 入口，递归处理整棵子树。 */
    fun tune(root: View?) {
        if (root !is ViewGroup) return
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            when (child) {
                is MaterialButton -> tuneButton(child)
                is EditText -> tuneEdit(child)
                else -> tune(child)
            }
        }
    }

    private fun tuneButton(button: MaterialButton) {
        button.includeFontPadding = false
        button.gravity = Gravity.CENTER
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            button.firstBaselineToTopHeight = 0
            button.lastBaselineToBottomHeight = 0
        }
    }

    /** 输入框（含 TextInputEditText）显式挂仿宋 */
    private fun tuneEdit(edit: EditText) {
        edit.includeFontPadding = false
        val tf = ResourcesCompat.getFont(edit.context, R.font.zhuque_fangsong)
        if (tf != null) edit.typeface = tf
    }
}
