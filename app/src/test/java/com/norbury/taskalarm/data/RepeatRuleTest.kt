package com.norbury.taskalarm.data

import com.norbury.taskalarm.data.RepeatRule.DAILY
import com.norbury.taskalarm.data.RepeatRule.TYPE_DATE
import com.norbury.taskalarm.data.RepeatRule.TYPE_ONCE
import com.norbury.taskalarm.data.RepeatRule.TYPE_WEEKDAY
import com.norbury.taskalarm.data.RepeatRule.WEEKDAYS
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 卡片重复缩写回归。
 * 曾经的 bug：TYPE_ONCE 的任务 repeatMask 默认全选（DAILY），
 * repeatShort 没按 repeatType 分流，一次性任务全显示成「每天」。
 */
class RepeatRuleTest {

    private fun task(
        type: Int = TYPE_WEEKDAY,
        mask: Int = DAILY,
        anchor: String = "",
        months: Int = 1,
        days: Int = 0
    ) = Task(
        name = "t",
        repeatType = type,
        repeatMask = mask,
        anchorDate = anchor,
        intervalMonths = months,
        intervalDays = days
    )

    @Test
    fun `一次性任务显示仅一次而不是每天`() {
        // 关键回归：once 的 mask 就是默认的 DAILY（全选），不能掉进 describe(mask)
        assertEquals("仅一次", RepeatRule.repeatShortLabel(task(type = TYPE_ONCE)))
    }

    @Test
    fun `每天与工作日周末不走样`() {
        assertEquals("每天", RepeatRule.repeatShortLabel(task(mask = DAILY)))
        assertEquals("工作日", RepeatRule.repeatShortLabel(task(mask = WEEKDAYS)))
    }

    @Test
    fun `按星期掩码显示具体星期`() {
        // 周三 = 1 shl 3
        assertEquals("周三", RepeatRule.repeatShortLabel(task(mask = 1 shl 3)))
    }

    @Test
    fun `按月任务显示每月几号`() {
        assertEquals("每月20号", RepeatRule.repeatShortLabel(
            task(type = TYPE_DATE, anchor = "2026-10-20", months = 1)
        ))
    }

    @Test
    fun `隔月任务显示每N月几号`() {
        assertEquals("每3月20号", RepeatRule.repeatShortLabel(
            task(type = TYPE_DATE, anchor = "2026-10-20", months = 3)
        ))
    }

    @Test
    fun `每N天任务显示间隔天数`() {
        assertEquals("每7天", RepeatRule.repeatShortLabel(
            task(type = TYPE_DATE, anchor = "2026-10-20", days = 7)
        ))
    }
}
