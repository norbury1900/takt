package com.norbury.taskalarm.util

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import kotlin.math.max

/**
 * 日期格的「完成度环」（参考稿 mobile-ui-replica_red 的 date progress ring）：
 * 一圈淡色底环 + 从正上方顺时针的进度弧，圆头线帽。
 *
 * 月历格用它替代纯色椭圆：过去没做完的日子按比例画弧（做了多少亮多少），
 * 全做完画满环，未来只用底环描边 —— 一眼看出一天的完成度。
 */
class RingDrawable(
    /** 0f..1f；0 = 只画底环 */
    private val progress: Float,
    trackColor: Int,
    progressColor: Int,
    strokePx: Int,
) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val track = trackColor
    private val arc = progressColor
    private val stroke = max(2, strokePx).toFloat()

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val cx = b.exactCenterX()
        val cy = b.exactCenterY()
        val r = (minOf(b.width(), b.height()) - stroke) / 2f
        paint.strokeWidth = stroke
        paint.color = track
        canvas.drawCircle(cx, cy, r, paint)
        val p = progress.coerceIn(0f, 1f)
        if (p > 0f) {
            paint.color = arc
            canvas.drawArc(cx - r, cy - r, cx + r, cy + r, -90f, 360f * p, false, paint)
        }
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(cf: ColorFilter?) {
        paint.colorFilter = cf
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
