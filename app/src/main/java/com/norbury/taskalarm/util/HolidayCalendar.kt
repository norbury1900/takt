package com.norbury.taskalarm.util

import java.util.Calendar

/**
 * 中国法定节假日与调休日历。
 *
 * 为什么需要它：把「工作日」简单等同于「周一到周五」是不准确的。
 * 国庆这类长假期间，周一到周五照样放假；而调休又会让某些周六、周日变成上班日。
 * 所以判断某一天该不该提醒，必须查法定日历，而不是只看星期几。
 *
 * 数据来源：国务院办公厅《关于 2026 年部分节假日安排的通知》（2025-11-04）、
 * 《关于 2027 年部分节假日安排的通知》（国办发明电〔2026〕10 号，2026-11-05）。
 * 数据内置在 App 里、纯本地计算、不联网 —— 符合数据不外传的要求。
 * 未覆盖的年份会优雅回退为「周一到周五」，并在环境自检页提示。
 *
 * 更新方式：国务院一般在每年 11 月公布次年安排，届时把新的区间补进
 * [HOLIDAY_RANGES] 与 [MAKEUP_WORKDAYS] 即可，其余逻辑无需改动。
 */
object HolidayCalendar {

    /** 一天的性质 */
    enum class DayType {
        /** 法定工作日（周一至周五，且不在放假区间） */
        WORKDAY,

        /** 周末休息日 */
        WEEKEND,

        /** 法定节假日（放假） */
        HOLIDAY,

        /** 调休补班（本该周末休息，但法定要求上班） */
        MAKEUP_WORKDAY
    }

    /** 放假区间（闭区间）。2026 年合计 33 天，2027 年合计 29 天 */
    private val HOLIDAY_RANGES = listOf(
        "2026-01-01" to "2026-01-03",   // 元旦：1/1(四)–1/3(六)，共 3 天
        "2026-02-15" to "2026-02-23",   // 春节：2/15(日)–2/23(一)，共 9 天
        "2026-04-04" to "2026-04-06",   // 清明：4/4(六)–4/6(一)，共 3 天
        "2026-05-01" to "2026-05-05",   // 劳动节：5/1(五)–5/5(二)，共 5 天
        "2026-06-19" to "2026-06-21",   // 端午：6/19(五)–6/21(日)，共 3 天
        "2026-09-25" to "2026-09-27",   // 中秋：9/25(五)–9/27(日)，共 3 天
        "2026-10-01" to "2026-10-07",   // 国庆：10/1(四)–10/7(三)，共 7 天
        // 2027（国办发明电〔2026〕10 号）
        "2027-01-01" to "2027-01-03",   // 元旦：1/1(五)–1/3(日)，共 3 天
        "2027-02-05" to "2027-02-13",   // 春节：2/5(除夕、五)–2/13(初八、六)，共 9 天
        "2027-04-03" to "2027-04-05",   // 清明：4/3(六)–4/5(一)，共 3 天
        "2027-05-01" to "2027-05-05",   // 劳动节：5/1(六)–5/5(三)，共 5 天
        "2027-06-09" to "2027-06-09",   // 端午：6/9(三)，共 1 天
        "2027-09-15" to "2027-09-15",   // 中秋：9/15(三)，共 1 天
        "2027-10-01" to "2027-10-07"    // 国庆：10/1(五)–10/7(四)，共 7 天
    )

    /** 调休补班日：周末但要上班 */
    private val MAKEUP_WORKDAYS = setOf(
        "2026-01-04",   // 元旦调休：周日上班
        "2026-02-14",   // 春节调休：周六上班
        "2026-02-28",   // 春节调休：周六上班
        "2026-05-09",   // 劳动节调休：周六上班
        "2026-09-20",   // 国庆调休：周日上班
        "2026-10-10",   // 国庆调休：周六上班
        // 2027
        "2027-01-31",   // 春节调休：周日上班
        "2027-02-14",   // 春节调休：周日上班
        "2027-05-08",   // 劳动节调休：周六上班
        "2027-09-26",   // 国庆调休：周日上班
        "2027-10-09"    // 国庆调休：周六上班
    )

    private val HOLIDAYS: Set<String> = expand(HOLIDAY_RANGES)

    /** 已覆盖的年份（用于提示"哪几年的数据是准的"） */
    fun coveredYears(): List<Int> =
        (HOLIDAYS + MAKEUP_WORKDAYS).mapNotNull { it.take(4).toIntOrNull() }.distinct().sorted()

    fun isCovered(dateKey: String): Boolean = dateKey.take(4).toIntOrNull() in coveredYears()

    /** 某一天的性质 */
    fun typeOf(dateKey: String): DayType {
        if (dateKey in HOLIDAYS) return DayType.HOLIDAY
        if (dateKey in MAKEUP_WORKDAYS) return DayType.MAKEUP_WORKDAY
        val dow = toCalendar(dateKey).get(Calendar.DAY_OF_WEEK)
        return if (dow == Calendar.SATURDAY || dow == Calendar.SUNDAY) {
            DayType.WEEKEND
        } else {
            DayType.WORKDAY
        }
    }

    /** 是否法定工作日（含调休补班的周末） */
    fun isWorkday(dateKey: String): Boolean {
        val t = typeOf(dateKey)
        return t == DayType.WORKDAY || t == DayType.MAKEUP_WORKDAY
    }

    /** 是否法定休息日（周末或法定假日） */
    fun isRestDay(dateKey: String): Boolean = !isWorkday(dateKey)

    /** 是否法定节假日放假 */
    fun isHoliday(dateKey: String): Boolean = typeOf(dateKey) == DayType.HOLIDAY

    /** 给界面用的一句话说明 */
    fun describe(type: DayType): String = when (type) {
        DayType.WORKDAY -> "工作日"
        DayType.WEEKEND -> "周末休息"
        DayType.HOLIDAY -> "法定节假日"
        DayType.MAKEUP_WORKDAY -> "调休补班（需上班）"
    }

    /** 今天是哪一类（日期键由调用方传入，便于测试） */
    fun todayType(todayKey: String): DayType = typeOf(todayKey)

    /* ---------------- 内部工具 ---------------- */

    private fun expand(ranges: List<Pair<String, String>>): Set<String> {
        val out = HashSet<String>()
        for ((from, to) in ranges) {
            val end = toCalendar(to)
            val cur = toCalendar(from)
            var guard = 0
            while (!cur.after(end) && guard < 400) {
                guard++
                out.add(keyOf(cur))
                cur.add(Calendar.DAY_OF_YEAR, 1)
            }
        }
        return out
    }

    private fun toCalendar(key: String): Calendar {
        val p = key.split("-")
        val y = p.getOrNull(0)?.toIntOrNull() ?: 1970
        val m = p.getOrNull(1)?.toIntOrNull() ?: 1
        val d = p.getOrNull(2)?.toIntOrNull() ?: 1
        return Calendar.getInstance().apply {
            set(y, m - 1, d, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
    }

    private fun keyOf(cal: Calendar): String =
        "%04d-%02d-%02d".format(
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH)
        )
}
