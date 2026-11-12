package com.norbury.taktu.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** RepeatRule.isLunarDue：农历每年任务 + 提前天数的到期判定 */
class LunarDueTest {

    @Test
    fun `正月廿八当天到期`() {
        // 2026 年正月廿八 = 公历 2026-03-16
        assertTrue(RepeatRule.isLunarDue("01-28", 0, "2026-03-16"))
    }

    @Test
    fun `提前一天_前一天到期`() {
        // 用户场景：每年正月廿八的生日，提前一天（正月廿七）提醒
        assertTrue(RepeatRule.isLunarDue("01-28", 1, "2026-03-15"))
        assertFalse(RepeatRule.isLunarDue("01-28", 1, "2026-03-16"))
    }

    @Test
    fun `非到期日不触发`() {
        assertFalse(RepeatRule.isLunarDue("01-28", 0, "2026-03-15"))
        assertFalse(RepeatRule.isLunarDue("01-28", 0, "2026-10-20"))
    }

    @Test
    fun `非法锚点不触发`() {
        assertFalse(RepeatRule.isLunarDue("", 0, "2026-03-16"))
        assertFalse(RepeatRule.isLunarDue("xx", 0, "2026-03-16"))
    }

    @Test
    fun `结束日期_过期不再触发`() {
        // 到期日恰好在结束日当天：仍触发；之后年份不再触发
        assertTrue(RepeatRule.isLunarDue("01-28", 0, "2026-03-16", "2026-03-16"))
        assertFalse(RepeatRule.isLunarDue("01-28", 0, "2026-03-16", "2026-03-15"))
        assertFalse(RepeatRule.isLunarDue("01-28", 0, "2027-03-16", "2026-03-15"))
        // 空结束日期 = 不限
        assertTrue(RepeatRule.isLunarDue("01-28", 0, "2026-03-16", ""))
    }

    @Test
    fun `间隔年_每N年触发一次`() {
        // 2027 正月廿八 = 2027-03-05（2027 春节 02-06）
        assertTrue(RepeatRule.isLunarDue("01-28", 0, "2026-03-16", "", 2, 2026))
        assertFalse(RepeatRule.isLunarDue("01-28", 0, "2027-03-05", "", 2, 2026))
        assertTrue(RepeatRule.isLunarDue("01-28", 0, "2027-03-05", "", 2, 2025))
        // 默认参数（不传间隔）= 每年
        assertTrue(RepeatRule.isLunarDue("01-28", 0, "2027-03-05", "", 1, 2026))
    }

    @Test
    fun `锚年_建档后首个该农历日`() {
        // 2026-10-20 建档 → 2026 正月廿八已过，锚到 2027
        val cal = java.util.GregorianCalendar(2026, java.util.Calendar.OCTOBER, 20)
        assertEquals(2027, RepeatRule.lunarBaseYear(1, 28, cal.timeInMillis))
        // 2026-01-01 建档 → 2026-03-16 当年还没到，锚 2026
        val jan = java.util.GregorianCalendar(2026, java.util.Calendar.JANUARY, 1)
        assertEquals(2026, RepeatRule.lunarBaseYear(1, 28, jan.timeInMillis))
    }
}
