package com.norbury.taktu.util

import java.time.LocalDate

/**
 * 农历 ↔ 公历换算（1900–2100，纯 JVM 可单测）。
 *
 * 核心是每年一个整数的 lunarInfo 表（闰月号在低 4 位，大小月位从 0x10000 右移），
 * 表格数据取自 solarlunar 3.1.0（jjonline/calendar.js 同源，公认精度）。
 * 锚点：1900-01-31 = 1900 年正月初一。
 *
 * ponytail: 不支持「闰月」日期的显式选择（生日落在闰月的极少），
 * 闰月只参与天数累计；需要显式闰月时在 UI 层加一个开关。
 */
object LunarCalendar {

    private const val MIN_YEAR = 1900
    private const val MAX_YEAR = 2100

    /** 1900-01-31 = 1900 年正月初一 */
    private val BASE = LocalDate.of(1900, 1, 31)

    private val LUNAR_INFO = intArrayOf(
        0x04bd8, 0x04ae0, 0x0a570, 0x054d5, 0x0d260, 0x0d950, 0x16554, 0x056a0, 0x09ad0, 0x055d2, // 1900-1909
        0x04ae0, 0x0a5b6, 0x0a4d0, 0x0d250, 0x1d255, 0x0b540, 0x0d6a0, 0x0ada2, 0x095b0, 0x14977, // 1910-1919
        0x04970, 0x0a4b0, 0x0b4b5, 0x06a50, 0x06d40, 0x1ab54, 0x02b60, 0x09570, 0x052f2, 0x04970, // 1920-1929
        0x06566, 0x0d4a0, 0x0ea50, 0x06e95, 0x05ad0, 0x02b60, 0x186e3, 0x092e0, 0x1c8d7, 0x0c950, // 1930-1939
        0x0d4a0, 0x1d8a6, 0x0b550, 0x056a0, 0x1a5b4, 0x025d0, 0x092d0, 0x0d2b2, 0x0a950, 0x0b557, // 1940-1949
        0x06ca0, 0x0b550, 0x15355, 0x04da0, 0x0a5b0, 0x14573, 0x052b0, 0x0a9a8, 0x0e950, 0x06aa0, // 1950-1959
        0x0aea6, 0x0ab50, 0x04b60, 0x0aae4, 0x0a570, 0x05260, 0x0f263, 0x0d950, 0x05b57, 0x056a0, // 1960-1969
        0x096d0, 0x04dd5, 0x04ad0, 0x0a4d0, 0x0d4d4, 0x0d250, 0x0d558, 0x0b540, 0x0b6a0, 0x195a6, // 1970-1979
        0x095b0, 0x049b0, 0x0a974, 0x0a4b0, 0x0b27a, 0x06a50, 0x06d40, 0x0af46, 0x0ab60, 0x09570, // 1980-1989
        0x04af5, 0x04970, 0x064b0, 0x074a3, 0x0ea50, 0x06b58, 0x05ac0, 0x0ab60, 0x096d5, 0x092e0, // 1990-1999
        0x0c960, 0x0d954, 0x0d4a0, 0x0da50, 0x07552, 0x056a0, 0x0abb7, 0x025d0, 0x092d0, 0x0cab5, // 2000-2009
        0x0a950, 0x0b4a0, 0x0baa4, 0x0ad50, 0x055d9, 0x04ba0, 0x0a5b0, 0x15176, 0x052b0, 0x0a930, // 2010-2019
        0x07954, 0x06aa0, 0x0ad50, 0x05b52, 0x04b60, 0x0a6e6, 0x0a4e0, 0x0d260, 0x0ea65, 0x0d530, // 2020-2029
        0x05aa0, 0x076a3, 0x096d0, 0x04afb, 0x04ad0, 0x0a4d0, 0x1d0b6, 0x0d250, 0x0d520, 0x0dd45, // 2030-2039
        0x0b5a0, 0x056d0, 0x055b2, 0x049b0, 0x0a577, 0x0a4b0, 0x0aa50, 0x1b255, 0x06d20, 0x0ada0, // 2040-2049
        0x14b63, 0x09370, 0x049f8, 0x04970, 0x064b0, 0x168a6, 0x0ea50, 0x06b20, 0x1a6c4, 0x0aae0, // 2050-2059
        0x092e0, 0x0d2e3, 0x0c960, 0x0d557, 0x0d4a0, 0x0da50, 0x05d55, 0x056a0, 0x0a6d0, 0x055d4, // 2060-2069
        0x052d0, 0x0a9b8, 0x0a950, 0x0b4a0, 0x0b6a6, 0x0ad50, 0x055a0, 0x0aba4, 0x0a5b0, 0x052b0, // 2070-2079
        0x0b273, 0x06930, 0x07337, 0x06aa0, 0x0ad50, 0x14b55, 0x04b60, 0x0a570, 0x054e4, 0x0d160, // 2080-2089
        0x0e968, 0x0d520, 0x0daa0, 0x16aa6, 0x056d0, 0x04ae0, 0x0a9d4, 0x0a4d0, 0x0d150, 0x0f252, // 2090-2099
        0x0d520 // 2100
    )

    private val MONTH_NAMES =
        arrayOf("正月", "二月", "三月", "四月", "五月", "六月", "七月", "八月", "九月", "十月", "冬月", "腊月")

    private val DIGITS = arrayOf("一", "二", "三", "四", "五", "六", "七", "八", "九", "十")

    /** 农历年 y 闰几月（0 = 无闰月） */
    fun leapMonth(lunarYear: Int): Int =
        if (lunarYear in MIN_YEAR..MAX_YEAR) LUNAR_INFO[lunarYear - MIN_YEAR] and 0xf else 0

    /** 农历年 y 平月 m（1–12）的天数（29 / 30） */
    private fun monthDays(lunarYear: Int, month: Int): Int =
        if (LUNAR_INFO[lunarYear - MIN_YEAR] and (0x10000 shr month) != 0) 30 else 29

    /** 农历年 y 闰月的天数（无闰月时无意义） */
    private fun leapMonthDays(lunarYear: Int): Int =
        if (LUNAR_INFO[lunarYear - MIN_YEAR] and 0x10000 != 0) 30 else 29

    private fun yearDays(lunarYear: Int): Int {
        var sum = 0
        for (m in 1..12) sum += monthDays(lunarYear, m)
        if (leapMonth(lunarYear) > 0) sum += leapMonthDays(lunarYear)
        return sum
    }

    /**
     * 农历（年, 月, 日）→ 公历；按平月算（闰月不作为可选目标，只参与累计）。
     * 越界 / 该月没有这一天（如小月传 30）返回 null。
     */
    fun lunarToSolar(lunarYear: Int, lunarMonth: Int, lunarDay: Int): LocalDate? {
        if (lunarYear !in MIN_YEAR..MAX_YEAR) return null
        if (lunarMonth !in 1..12 || lunarDay !in 1..30) return null
        if (lunarDay > monthDays(lunarYear, lunarMonth)) return null

        var offset = 0
        for (y in MIN_YEAR until lunarYear) offset += yearDays(y)
        val leap = leapMonth(lunarYear)
        for (m in 1 until lunarMonth) {
            offset += monthDays(lunarYear, m)
            if (m == leap) offset += leapMonthDays(lunarYear) // 闰月排在该月之后
        }
        offset += lunarDay - 1
        return BASE.plusDays(offset.toLong())
    }

    /** 农历月名：1 → 正月 … 12 → 腊月 */
    fun monthName(month: Int): String =
        MONTH_NAMES.getOrElse(month - 1) { "$month 月" }

    /** 农历日名：1 → 初一 … 28 → 廿八 … 30 → 三十 */
    fun dayName(day: Int): String = when {
        day in 1..10 -> "初" + DIGITS[day - 1]
        day == 20 -> "二十"
        day == 30 -> "三十"
        day in 11..19 -> "十" + DIGITS[day - 11]
        else -> "廿" + DIGITS[day - 21] // 21..29
    }

    /** "MM-dd"（农历月-日，存于 anchorDate）→ 人类描述，如「每年农历正月廿八 · 提前 1 天」 */
    fun describe(anchorDate: String, leadDays: Int): String {
        val p = anchorDate.split("-")
        val m = p.getOrNull(0)?.toIntOrNull() ?: return "未设置农历日期"
        val d = p.getOrNull(1)?.toIntOrNull() ?: return "未设置农历日期"
        val base = "每年农历${monthName(m)}${dayName(d)}"
        return if (leadDays > 0) "$base · 提前 $leadDays 天" else base
    }

    /**
     * 农历 (月, 日) 在 from 当天及之后的下一个公历日期（供「仅一次 + 农历」当场换算落库）。
     * 跨年扫前后各一年，找不到（月日越界）返回 null。
     */
    fun nextSolar(month: Int, day: Int, from: LocalDate): LocalDate? {
        for (y in from.year - 1..from.year + 1) {
            val solar = lunarToSolar(y, month, day) ?: continue
            if (!solar.isBefore(from)) return solar
        }
        return null
    }

    /**
     * 公历 → 农历（年, 月, 日）；供结束日期等公历存值的农历回显。
     * ponytail: 落在闰月里的日期映射回平月显示（闰月生日极罕见，见类头注记）。
     */
    fun solarToLunar(date: LocalDate): Triple<Int, Int, Int>? {
        if (date.isBefore(BASE)) return null
        var offset = java.time.temporal.ChronoUnit.DAYS.between(BASE, date).toInt()
        var y = MIN_YEAR
        while (y < MAX_YEAR && offset >= yearDays(y)) {
            offset -= yearDays(y)
            y++
        }
        if (offset < 0) return null
        val leap = leapMonth(y)
        var m = 1
        while (m <= 12) {
            val md = monthDays(y, m)
            if (offset < md) return Triple(y, m, offset + 1)
            offset -= md
            if (m == leap) {
                val ld = leapMonthDays(y)
                if (offset < ld) return Triple(y, m, offset + 1) // 闰 X 月 → 平月日
                offset -= ld
            }
            m++
        }
        return null
    }
}
