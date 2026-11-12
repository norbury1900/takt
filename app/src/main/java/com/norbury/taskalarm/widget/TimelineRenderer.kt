package com.norbury.taskalarm.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.core.content.res.ResourcesCompat
import com.norbury.taskalarm.R
import com.norbury.taskalarm.data.TimelineModel
import com.norbury.taskalarm.data.layout

/**
 * 迷你时间轴的渲染层（v3.34.42 Demo_3 落地；v3.34.44 行级强化+深色；
 * v3.34.45 改整卡自绘）。
 *
 * RemoteViews 不允许自定义 View（连 <View> 都「Class not allowed to be inflated」，
 * v3.34.41 logcat 实锤），且 **XML 里的 fontFamily 到了启动器进程会被吞掉**
 * （v3.34.44 实测表头/胶囊不生效）——所以 v3.34.45 起整卡全部画进位图：
 * 表头、分隔线、时间轴、完成胶囊、空态，字体统一朱雀仿宋。
 * 胶囊的点击由布局里一块透明 TextView 盖在固定位置实现（见 widget_today.xml）。
 *
 * 纯绘制：布局算法在 data/WidgetTimeline.layout（有单测），这里只管落画笔。
 */
object TimelineRenderer {

    /** 竖脊线 x（dp）；时刻标签在其左侧右对齐，任务名在其右侧。
     *  34 太贴边：标签右对齐在 SPINE_X-10，「11:25」11sp 约 30dp 宽，左端会伸出位图被裁
     *  （v3.34.46 用户实测「字边缘显示不全」），48 给标签留出 ~8dp 左边距。 */
    private const val SPINE_X = 48f
    private const val DOT_R = 4.5f
    private const val TEXT_TIME = 11f
    private const val TEXT_NAME = 12.5f
    private const val TEXT_SUB = 10.5f
    private const val ROW_H = 30f

    /** 整卡分区：表头（标题+右侧信息+分隔线）占顶部，完成胶囊占右下角 */
    private const val HEADER_H = 34f
    private const val PILL_H = 30f
    private const val PILL_W = 132f
    private const val PILL_GAP = 4f

    /**
     * 画整卡。位图透明底，卡片圆角背景由根布局 drawable 提供。
     * @param emptyText 非空 = 没有任何行，居中显示它（「今日事毕」/「今天没有待办」）
     * @param pillText 非空 = 右下角画完成胶囊；调用方须同步显示透明点击层
     */
    fun render(
        context: Context,
        model: TimelineModel,
        widthDp: Float,
        heightDp: Float,
        dark: Boolean,
        headerRight: String,
        emptyText: String?,
        pillText: String?,
    ): Bitmap {
        val d = context.resources.displayMetrics.density
        val bmp = Bitmap.createBitmap(
            (widthDp * d).toInt().coerceAtLeast(1),
            (heightDp * d).toInt().coerceAtLeast(1),
            Bitmap.Config.ARGB_8888
        )
        val c = Canvas(bmp)
        c.scale(d, d) // 之后一律按 dp 画

        // 深色配色（Demo_4 方案三）与浅色(widget_* 资源)同构；不设 night 资源变体，
        // 由 refresh 判断 uiMode 后传参，这里不碰 Configuration
        val stroke = context.getColor(if (dark) R.color.widget_dark_stroke else R.color.widget_stroke)
        val accent = context.getColor(if (dark) R.color.widget_dark_accent else R.color.widget_accent)
        val sub = context.getColor(if (dark) R.color.widget_dark_sub else R.color.widget_text_sub)
        val text = context.getColor(if (dark) R.color.widget_dark_text else R.color.widget_text)
        val hl = if (dark) 0xFF332B23.toInt() else 0xFFF7EBE2.toInt()
        val pillBg = context.getColor(if (dark) R.color.widget_dark_pill_bg else R.color.widget_pill_bg)
        val pillText2 = context.getColor(if (dark) R.color.widget_dark_pill_text else R.color.widget_pill_text)
        val serif = ResourcesCompat.getFont(context, R.font.zhuque_fangsong)

        fun label(sizeDp: Float, color: Int, bold: Boolean = false, alignRight: Boolean = false) =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = serif
                textSize = sizeDp
                this.color = color
                isFakeBoldText = bold
                textAlign = if (alignRight) Paint.Align.RIGHT else Paint.Align.LEFT
            }

        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1f }

        // ---- 表头：今天 | 时间 · 还剩 N 件，下方细分隔线（RemoteViews 白名单里
        //      没有 <View>，分隔线本来就是画的；v3.34.45 连字一起进位图）----
        c.drawText(context.getString(R.string.widget_today_label), 6f, 22f, label(15f, text, bold = true))
        c.drawText(headerRight, widthDp - 6f, 21f, label(12f, accent, alignRight = true))
        line.color = stroke
        c.drawLine(6f, HEADER_H - 6f, widthDp - 6f, HEADER_H - 6f, line)

        // ---- 时间轴区 ----
        val regionTop = HEADER_H + 2f
        val pillZone = if (pillText != null) PILL_H + PILL_GAP else 0f
        val rowsH = (heightDp - regionTop - pillZone - 2f).coerceAtLeast(40f)
        val tl = layout(model, rowsH)

        c.save()
        c.translate(0f, regionTop)

        if (emptyText != null) {
            val pEmpty = label(13f, if (model.total > 0) accent else sub)
            pEmpty.textAlign = Paint.Align.CENTER // 中心点坐标，不设会从中间起排偏右
            c.drawText(
                emptyText, widthDp / 2f, rowsH / 2f - (pEmpty.ascent() + pEmpty.descent()) / 2f,
                pEmpty
            )
            c.restore()
            return bmp
        }

        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val hlPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = hl }

        // 竖脊线：首行到末行
        if (tl.placed.isNotEmpty()) {
            line.color = stroke
            c.drawLine(SPINE_X, tl.placed.first().y, SPINE_X, tl.placed.last().y, line)
        }

        val pTime = label(TEXT_TIME, sub, alignRight = true)
        val maxNameW = widthDp - (SPINE_X + 14f) - 8f
        for (p in tl.placed) {
            val e = p.entry

            // 催促中：整行淡陶土高亮底
            if (e.urgent) {
                c.drawRoundRect(2f, p.y - ROW_H / 2, widthDp - 2f, p.y + ROW_H / 2, 10f, 10f, hlPaint)
            }

            // 圆点：已完成=灰 / 催促=光晕 / 超时未做=空心 / 普通=陶土
            when {
                e.done -> {
                    dot.style = Paint.Style.FILL
                    dot.color = stroke
                }
                e.urgent -> {
                    dot.style = Paint.Style.FILL
                    dot.color = accent
                    val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE; strokeWidth = 2f
                        this.color = accent; alpha = 110
                    }
                    c.drawCircle(SPINE_X, p.y, DOT_R + 3.5f, ring)
                }
                e.overdue -> {
                    dot.style = Paint.Style.STROKE
                    dot.strokeWidth = 1.5f
                    dot.color = accent
                }
                else -> {
                    dot.style = Paint.Style.FILL
                    dot.color = accent
                }
            }
            c.drawCircle(SPINE_X, p.y, if (e.urgent) DOT_R + 1.5f else DOT_R, dot)
            dot.style = Paint.Style.FILL

            c.drawText(p.timeLabel, SPINE_X - 10, p.y - (pTime.ascent() + pTime.descent()) / 2f, pTime)

            // 任务名（超宽截断）+ 已完成删除线 + 进度小字
            val pName = label(
                TEXT_NAME, when {
                    e.urgent -> accent
                    e.done -> sub
                    else -> text
                }
            )
            var name = e.name
            while (pName.measureText(name) > maxNameW && name.length > 1) name = name.dropLast(1)
            val truncated = name != e.name
            if (truncated) name += "…"
            val nameX = SPINE_X + 14f
            c.drawText(name, nameX, p.y - (pName.ascent() + pName.descent()) / 2f, pName)
            if (e.done) {
                line.color = if (dark) sub else 0xFFB4AA99.toInt()
                val y = p.y + 1f
                c.drawLine(nameX, y, nameX + pName.measureText(name), y, line)
            }
            if (e.sub.isNotEmpty() && !truncated) {
                val pSub = label(TEXT_SUB, sub)
                val subX = nameX + pName.measureText(name) + 8f
                if (subX + pSub.measureText(e.sub) < widthDp - 6f) {
                    c.drawText(e.sub, subX, p.y - (pSub.ascent() + pSub.descent()) / 2f, pSub)
                }
            }
        }

        // 「现在」线：陶土色横穿（无小字）
        tl.nowY?.let { y ->
            line.color = accent
            line.strokeWidth = 1.5f
            c.drawLine(6f, y, widthDp - 6f, y, line)
        }
        c.restore()

        // ---- 完成胶囊（右下角，四周留 10dp：卡片背景圆角 16dp，顶到角上会被裁 ——
        //      位置固定，点击层 widget_today.xml 里用同值 margin 对齐）----
        if (pillText != null) {
            val inset = 10f
            val right = widthDp - inset
            val bottom = heightDp - inset
            val left = right - PILL_W
            val top = bottom - PILL_H
            val pPill = label(12.5f, pillText2)
            pPill.textAlign = Paint.Align.CENTER // drawText 给的是中心 x，不设会从中心起排偏右溢出
            c.drawRoundRect(
                left, top + 2f, right, bottom, 9f, 9f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pillBg }
            )
            var pt = pillText ?: ""
            val maxPillW = PILL_W - 16f
            while (pPill.measureText(pt) > maxPillW && pt.length > 1) pt = pt.dropLast(1)
            if (pt != pillText) pt += "…"
            c.drawText(
                pt, left + PILL_W / 2f, top + PILL_H / 2f - (pPill.ascent() + pPill.descent()) / 2f + 1f,
                pPill
            )
        }
        return bmp
    }
}
