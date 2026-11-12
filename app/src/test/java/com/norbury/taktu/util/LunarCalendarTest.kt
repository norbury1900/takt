package com.norbury.taktu.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/**
 * 农历换算锚点验证：春节、端午、中秋这些有公认公历日期的节气日。
 * 用户场景：每年正月廿八的生日，提前一天（正月廿七）10:00 提醒。
 */
class LunarCalendarTest {

    @Test
    fun `春节锚点_2026年正月初一是2月17日`() {
        assertEquals(LocalDate.of(2026, 2, 17), LunarCalendar.lunarToSolar(2026, 1, 1))
    }

    @Test
    fun `春节锚点_2025与2024`() {
        assertEquals(LocalDate.of(2025, 1, 29), LunarCalendar.lunarToSolar(2025, 1, 1))
        assertEquals(LocalDate.of(2024, 2, 10), LunarCalendar.lunarToSolar(2024, 1, 1))
    }

    @Test
    fun `正月廿八_2026年是3月16日`() {
        assertEquals(LocalDate.of(2026, 3, 16), LunarCalendar.lunarToSolar(2026, 1, 28))
    }

    @Test
    fun `端午_2025年五月初五是5月31日`() {
        assertEquals(LocalDate.of(2025, 5, 31), LunarCalendar.lunarToSolar(2025, 5, 5))
    }

    @Test
    fun `中秋_2025年八月十五是10月6日`() {
        assertEquals(LocalDate.of(2025, 10, 6), LunarCalendar.lunarToSolar(2025, 8, 15))
    }

    @Test
    fun `非法输入_返回null`() {
        assertNull(LunarCalendar.lunarToSolar(1899, 1, 1))   // 表前
        assertNull(LunarCalendar.lunarToSolar(2101, 1, 1))   // 表后
        assertNull(LunarCalendar.lunarToSolar(2026, 0, 1))   // 月越界
        assertNull(LunarCalendar.lunarToSolar(2026, 1, 31))  // 日越界
        assertNull(LunarCalendar.lunarToSolar(2026, 4, 30))  // 小月没有三十
    }

    @Test
    fun `中文月日名`() {
        assertEquals("正月", LunarCalendar.monthName(1))
        assertEquals("腊月", LunarCalendar.monthName(12))
        assertEquals("初一", LunarCalendar.dayName(1))
        assertEquals("廿八", LunarCalendar.dayName(28))
        assertEquals("三十", LunarCalendar.dayName(30))
    }

    @Test
    fun `描述文案_带提前天数`() {
        assertEquals("每年农历正月廿八", LunarCalendar.describe("01-28", 0))
        assertEquals("每年农历正月廿八 · 提前 1 天", LunarCalendar.describe("01-28", 1))
    }

    @Test
    fun `公历转农历_与正向换算往返一致`() {
        assertEquals(Triple(2026, 1, 28), LunarCalendar.solarToLunar(LocalDate.of(2026, 3, 16)))
        assertEquals(Triple(2025, 1, 1), LunarCalendar.solarToLunar(LocalDate.of(2025, 1, 29)))
        assertEquals(Triple(2024, 1, 1), LunarCalendar.solarToLunar(LocalDate.of(2024, 2, 10)))
        // 往返：随机几个锚点日 转过去再转回来 = 原日期
        for (y in 2024..2027) {
            for (m in intArrayOf(1, 5, 8, 12)) {
                val solar = LunarCalendar.lunarToSolar(y, m, 15) ?: continue
                assertEquals(Triple(y, m, 15), LunarCalendar.solarToLunar(solar))
            }
        }
    }

    @Test
    fun `仅一次农历选日_取下一个公历日`() {
        // 2026 春节 02-17 → 正月初五 = 02-21
        assertEquals(
            LocalDate.of(2026, 2, 21),
            LunarCalendar.nextSolar(1, 5, LocalDate.of(2026, 2, 10))
        )
        // 已过今年初五 → 取明年（2027 春节 02-06 → 初五 02-10）
        assertEquals(
            LocalDate.of(2027, 2, 10),
            LunarCalendar.nextSolar(1, 5, LocalDate.of(2026, 2, 25))
        )
        // 当天命中（含 from 当天）
        assertEquals(
            LocalDate.of(2026, 2, 21),
            LunarCalendar.nextSolar(1, 5, LocalDate.of(2026, 2, 21))
        )
    }
}
