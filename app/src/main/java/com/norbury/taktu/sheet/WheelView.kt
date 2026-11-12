package com.norbury.taktu.sheet

import android.app.Activity
import android.os.Build
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.norbury.taktu.R
import kotlin.math.abs

/**
 * 单个循环滚轮 —— **wrap-around 轮盘**，与 iOS 时间滚轮同款手感。
 * 从 TimeSheet 提取为公共组件：时间选择、农历月日联动选择共用同一只轮盘。
 *
 * 为什么要循环：早期版本是有边界的列表（分钟只有 00…55 共 12 格），
 * 一旦停在 `00`，「往上」就没有任何内容可滚，RecyclerView 的 scrollBy 返回 0，
 * 表现就是「分钟彻底拉不动」。小时停在 20 时上下都有余量，所以只有小时能动 ——
 * 这个现象是**边界**造成的，不是卡顿造成的。
 *
 * 循环做法：把真实值重复 [LOOP] 遍当作虚拟数据（getItemCount = 真实条数 × LOOP），
 * 起点放在正中间，两端都还有几百格可滚；对外只暴露 [selectedIndex]（真实下标）。
 * 这样任何方向都不会撞墙，也让 `canScrollVertically(-1)` 恒为真 ——
 * 底部弹窗的拖拽拦截就不会来抢手势。
 *
 * 视觉（连续函数，随离中心的距离平滑变化）：
 *  - scale：中心 1.35 → 每格外衰减 0.22（iOS picker 的纵深感）
 *  - 颜色：正中间一格主色，其余次要色；透明度随距离渐暗
 *  - **全程只用 scale/alpha/color，不动 textSize** —— textSize 变化会触发
 *    requestLayout，滚动中反复重布局会让轮盘一顿一顿的（用户感知"拉不动"）
 *
 * [settled]（虚拟下标）只在停稳（IDLE）后更新，配合 LinearSnapHelper 保证精确居中。
 *
 * @param values 真实值列表（如 24 个小时、"初一…三十"）
 * @param initialIndex 初始选中下标（真实下标，0..values.size-1）
 * @param onSettled 停稳回调（真实下标）—— 联动轮盘（农历月变 → 日列表变）用它
 */
class WheelView(
    activity: Activity,
    private val values: List<String>,
    initialIndex: Int,
    private val onSettled: ((Int) -> Unit)? = null
) : RecyclerView(activity) {

    private val itemPx = (44 * activity.resources.displayMetrics.density).toInt()

    /** 轮盘虚拟循环倍数：真实值重复这么多遍，起点放中间 —— 见类注释 */
    private val LOOP = 100

    /** 虚拟总条数；起点取正中间并对齐到真实值的起始偏移 */
    private val repeat = values.size * LOOP
    private val midBase = (repeat / 2 / values.size) * values.size
    private val startIndex = midBase + initialIndex.coerceIn(values.indices)

    private var settled = startIndex

    /** 上一次触发反馈的中心索引，用于"每跨一格只 tick 一次" */
    private var lastTick = startIndex

    /** 对外只给真实下标：0..values.size-1 */
    val selectedIndex: Int get() = settled % values.size

    init {
        setHasFixedSize(true)
        isNestedScrollingEnabled = true
        overScrollMode = OVER_SCROLL_NEVER
        layoutManager = LinearLayoutManager(activity)
        adapter = object : Adapter<Holder>() {
            override fun onCreateViewHolder(p: ViewGroup, vt: Int) =
                Holder(TextView(activity).apply {
                    gravity = Gravity.CENTER
                    textSize = 17f
                    layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, itemPx)
                })

            override fun onBindViewHolder(h: Holder, pos: Int) {
                h.tv.text = values[pos % values.size]
            }

            override fun getItemCount() = repeat
        }
        clipToPadding = false
        setPadding(0, itemPx * 2, 0, itemPx * 2)
        LinearSnapHelper().attachToRecyclerView(this)
        // 抢在底部弹窗的拖拽拦截之前表态：这个竖向手势归滚轮自己处理，别来抢
        setOnTouchListener { _, ev ->
            val a = ev.actionMasked
            if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_MOVE) {
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            false   // 不消费，交回 RecyclerView 自己处理滚动
        }
        addOnScrollListener(object : OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                refreshStyles()
                // 滚动中也按跨格给反馈：快速翻动时一格一响，和系统滚轮一致
                val pos = centerIndex()
                if (pos != RecyclerView.NO_POSITION && pos != lastTick) {
                    lastTick = pos
                    tick()
                }
            }

            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    val pos = centerIndex()
                    if (pos != RecyclerView.NO_POSITION) settled = pos
                    refreshStyles()
                    recenterIfNeeded()
                    onSettled?.invoke(selectedIndex)
                }
            }
        })
        // 首屏：直接把初始项滚到正中（offset=0 相对 padding 边，正好落在视口中心）
        post {
            (layoutManager as LinearLayoutManager).scrollToPositionWithOffset(startIndex, 0)
            refreshStyles()
        }
    }

    /**
     * 兜底：万一被甩到虚拟列表的端点附近（连续快速翻几百格才会发生），
     * 静默跳回中间对等位置。取模后画面上的值完全一样，肉眼看不出跳动。
     */
    private fun recenterIfNeeded() {
        if (settled >= values.size && settled <= repeat - values.size) return
        val target = midBase + (settled % values.size)
        settled = target
        lastTick = target
        (layoutManager as LinearLayoutManager).scrollToPositionWithOffset(target, 0)
    }

    /** 跨格反馈：触感优先（CLOCK_TICK，API 34 以下退化为键盘敲击），不发声避免吵 */
    private fun tick() {
        val constant = if (Build.VERSION.SDK_INT >= 34) {
            HapticFeedbackConstants.CLOCK_TICK
        } else {
            HapticFeedbackConstants.KEYBOARD_TAP
        }
        performHapticFeedback(constant)
    }

    private fun centerIndex(): Int {
        val center = height / 2f
        var best = RecyclerView.NO_POSITION
        var bestDist = Float.MAX_VALUE
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val cy = (child.top + child.bottom) / 2f
            val dist = abs(cy - center)
            if (dist < bestDist) {
                bestDist = dist
                best = getChildAdapterPosition(child)
            }
        }
        return best
    }

    private fun refreshStyles() {
        val center = height / 2f
        val accent = ContextCompat.getColor(context, R.color.accent)
        val textSub = ContextCompat.getColor(context, R.color.app_text_sub)
        for (i in 0 until childCount) {
            val child = getChildAt(i) as? TextView ?: continue
            val cy = (child.top + child.bottom) / 2f
            // 归一化距离：0 = 正中间，1 = 相邻一格
            val dist = (abs(cy - center) / itemPx).coerceAtMost(2f)
            val near = dist < 0.5f
            child.pivotX = child.width / 2f
            child.pivotY = child.height / 2f
            val s = 1.35f - 0.22f * dist
            child.scaleX = s
            child.scaleY = s
            child.setTextColor(if (near) accent else textSub)
            child.alpha = (1f - 0.22f * dist).coerceIn(0.4f, 1f)
        }
    }

    private class Holder(val tv: TextView) : RecyclerView.ViewHolder(tv)
}
