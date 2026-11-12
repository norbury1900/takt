package com.norbury.taskalarm.util

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.norbury.taskalarm.R
import com.norbury.taskalarm.data.AppDatabase
import java.util.Calendar

/**
 * GitHub 风格贡献热力图（v3.34.72）：7 行（周日→周六）× 26 列（周），
 * 一格 = 一天，红色深浅 = 当天完成度（该做的任务里完成了几件）。
 * 纯 onDraw 自绘——182 个格子没必要上 RecyclerView。
 */
class HeatmapView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private var values: Map<String, Float> = emptyMap()

    /** 26 周 × 7 天的日期 key，列 = 周、行 = 星期几 */
    private val days = ArrayList<String>(26 * 7)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** 传入 日期key(yyyy-MM-dd) -> 完成度(0..1)；没出现的日期 = 那天没任务 */
    fun setData(map: Map<String, Float>) {
        values = map
        days.clear()
        val cal = Calendar.getInstance()
        // 起点 = 26 周前那周的周日（列与星期对齐），最后一列是本周（今天之后的格不画）
        cal.add(Calendar.DAY_OF_YEAR, -(26 * 7 - 1))
        cal.add(Calendar.DAY_OF_YEAR, -(cal.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY))
        repeat(26 * 7) {
            days.add(AppDatabase.keyOf(cal.time))
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        invalidate()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = MeasureSpec.getSize(widthSpec)
        val gap = dp(2)
        setMeasuredDimension(w, 7 * cell(w, gap) + 6 * gap)
    }

    private fun cell(width: Int, gap: Int): Int =
        ((width - gap * 25) / 26).coerceAtLeast(dp(6)).coerceAtMost(dp(14))

    override fun onDraw(canvas: Canvas) {
        if (days.isEmpty()) return
        val gap = dp(2).toFloat()
        val c = cell(width, dp(2)).toFloat()
        val today = AppDatabase.todayKey()
        val accent = ContextCompat.getColor(context, R.color.accent)
        // 先铺一层「没数据」底，再逐格叠有数据的红
        // （v3.34.76 背板改纯白：原 app_card_alt 半透白在白底上不可见，换淡灰）
        paint.color = 0x1E000000
        for (col in 0 until 26) for (row in 0 until 7) {
            val l = col * (c + gap)
            val t = row * (c + gap)
            canvas.drawRoundRect(l, t, l + c, t + c, dp(3).toFloat(), dp(3).toFloat(), paint)
        }
        days.forEachIndexed { i, key ->
            if (key > today) return@forEachIndexed
            val ratio = values[key] ?: return@forEachIndexed
            val alpha = when {
                ratio <= 0f -> 0x59   // 该做但一件没做：最淡一档红
                ratio < 0.34f -> 0x8C
                ratio < 0.67f -> 0xBF
                else -> 0xFF
            }
            paint.color = ColorUtils.setAlphaComponent(accent, alpha)
            val l = (i / 7) * (c + gap)
            val t = (i % 7) * (c + gap)
            canvas.drawRoundRect(l, t, l + c, t + c, dp(3).toFloat(), dp(3).toFloat(), paint)
        }
    }
}
