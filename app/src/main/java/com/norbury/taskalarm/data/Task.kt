package com.norbury.taskalarm.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.norbury.taskalarm.util.HolidayCalendar
import java.util.Calendar

/** 重复规则：用 7 位掩码表示周日~周六，位 0 为周日 */
object RepeatRule {
    const val DAILY = 0b1111111          // 每天
    const val WEEKDAYS = 0b0111110       // 周一到周五
    const val WEEKEND = 0b1000001        // 周日 + 周六

    /** 重复类型 */
    const val TYPE_WEEKDAY = 0   // 按星期掩码
    const val TYPE_DATE = 1      // 按日期（每 N 个月的某号，可设结束日期）
    const val TYPE_ONCE = 2      // 只响一次（在某一天），购物、办证件这类事用
    const val TYPE_LUNAR = 3     // 农历每年（生日、传统节日）：anchorDate 存农历 "MM-dd"

    /**
     * 期间内方式（统一「重复时间段」的第二段，编辑页芯片与 [resolvePlan] 共用常量）：
     * 按星期几 / 每 N 天 / 每月 N 号。农历任务没有方式（按年循环，间隔用「每 N 年」）。
     */
    const val PAT_WEEKDAY = 0
    const val PAT_EVERY_N = 1
    const val PAT_MONTHLY = 2
    const val PAT_YEARLY = 3

    /** 落库用的重复方案（[resolvePlan] 的产物） */
    data class RepeatPlan(
        val repeatType: Int,
        /** 公历开始日 yyyy-MM-dd；农历任务存 "MM-dd" 月日 */
        val anchorDate: String,
        /** 农历任务复用为「每 N 年」 */
        val intervalMonths: Int,
        val intervalDays: Int,
        /** 结束日期（含当天），空串 = 长期不结束 */
        val endDate: String,
        /** 日期型任务的「每月几号」多选：bit(d-1) 表示 d 号；0 = 沿用锚点日 */
        val repeatMask: Int = 0
    )

    /**
     * 「显式多选」标记位（bit31）。日期型任务的 repeatMask 默认是 DAILY(127)，
     * 不标记的话老任务会被误认为「每月 1-7 号」——只有带这一位才当多选掩码解析。
     */
    const val MASK_EXPLICIT = 1 shl 31

    /** 掩码 → 日期列表（1..31） */
    fun daysOf(mask: Int): List<Int> =
        (1..31).filter { (mask and (1 shl (it - 1))) != 0 }

    /** 日期列表 → 带显式标记的掩码 */
    fun maskOfDays(days: Collection<Int>): Int =
        days.filter { it in 1..31 }.fold(MASK_EXPLICIT) { acc, d -> acc or (1 shl (d - 1)) }

    /** 「整月」掩码：每年任务只选月份时，该月每天都要提醒 */
    val FULL_MONTH_MASK: Int = maskOfDays((1..31).toList())

    /** 月份掩码 → 月份列表（1..12） */
    fun monthsOf(mask: Int): List<Int> =
        (1..12).filter { (mask and (1 shl (it - 1))) != 0 }

    /** 月份列表 → 掩码 */
    fun maskOfMonths(months: Collection<Int>): Int =
        months.filter { it in 1..12 }.fold(0) { acc, m -> acc or (1 shl (m - 1)) }

    /** 日期型任务可用的多选掩码；没显式选过返回 0（沿用锚点日） */
    fun dayMaskOf(raw: Int): Int =
        if ((raw and MASK_EXPLICIT) != 0) raw and MASK_EXPLICIT.inv() else 0

    /**
     * 统一「重复时间段」→ 落库字段（纯函数，便于单测；用户 2026-10-20 定案，v3.35）。
     *
     * 一个入口覆盖四种形态，不再单独设按钮：
     *  - **农历**（startLunar）：TYPE_LUNAR，anchorDate 只存月日（按年重复），
     *    intervalMonths 复用为「每 N 年」；结束日期由调用方换算成公历后传入。
     *  - **仅一次**：公历、按星期方式、开始 = 结束（同一天）→ TYPE_ONCE。
     *  - **自定义期间**：公历 + 起了止 → 按方式落 TYPE_WEEKDAY（anchor=开始日，
     *    引擎认长度 10 的 anchorDate 作起点）/ TYPE_DATE。
     *  - **永久**：结束日期为空串 → 同自定义，只是没有结束日（「从开始时间起一直重复」）。
     *
     * 结束日期早于开始日期不在这一层拦（UI 保存前校验并提示），这里只做映射。
     */
    fun resolvePlan(
        startLunar: Boolean,
        startKey: String,
        endKey: String,
        pattern: Int,
        intervalDays: Int = 0,
        intervalYears: Int = 1,
        lunarMonth: Int = 1,
        lunarDay: Int = 1,
        dayMask: Int = 0
    ): RepeatPlan {
        if (startLunar) {
            return RepeatPlan(
                repeatType = TYPE_LUNAR,
                anchorDate = "%02d-%02d".format(
                    lunarMonth.coerceIn(1, 12), lunarDay.coerceIn(1, 30)
                ),
                intervalMonths = intervalYears.coerceAtLeast(1),
                intervalDays = 0,
                endDate = endKey
            )
        }
        val start = startKey
        val once = pattern == PAT_WEEKDAY && endKey.isNotBlank() && endKey == start
        return when {
            once -> RepeatPlan(TYPE_ONCE, start, 1, 0, endKey)
            pattern == PAT_EVERY_N ->
                RepeatPlan(TYPE_DATE, start, 1, intervalDays.coerceAtLeast(1), endKey)
            // 每年：锚点日期的「月」定月份，日由多选掩码决定（v3.34.124）
            pattern == PAT_YEARLY -> RepeatPlan(TYPE_DATE, start, 12, 0, endKey, dayMask)
            // 每月：锚点日期的「日」定默认日期，多选时以掩码为准（intervalMonths=1）
            pattern == PAT_MONTHLY -> RepeatPlan(TYPE_DATE, start, 1, 0, endKey, dayMask)
            else -> RepeatPlan(TYPE_WEEKDAY, start, 1, 0, endKey)
        }
    }

    /** Calendar.DAY_OF_WEEK(1=周日..7=周六) 转成掩码位 */
    fun bitOf(calendarDayOfWeek: Int): Int = 1 shl (calendarDayOfWeek - 1)

    fun matches(mask: Int, calendarDayOfWeek: Int): Boolean =
        (mask and bitOf(calendarDayOfWeek)) != 0

    fun todayMask(): Int = bitOf(Calendar.getInstance().get(Calendar.DAY_OF_WEEK))

    /**
     * 按星期任务的起点：anchorDate 存了完整日期（长度 10）就是它；
     * 为空（老任务 / 导入时起点丢了）时**按建档日兜底**。
     *
     * 兜底的意义（v3.34.7，用户实测 bug）：起点整个丢掉后，按星期任务
     * 会从「mask 命中那天起天天触发」——本周新建的任务会出现在上周三、
     * 上上周三的计划页月历和统计趋势里。任务不可能在它被创建之前就该做，
     * 所以建档日是唯一合理的隐式起点。老任务（anchorDate 本就是空）
     * 行为不变：建档日通常远早于当前日期，不影响任何进行中的任务。
     */
    fun weekdayStartKey(task: Task): String =
        if (task.anchorDate.length == 10) task.anchorDate
        else AppDatabase.keyOf(java.util.Date(task.createdAt))

    /**
     * 日历感知版：该不该在这一天提醒。
     *
     * 与 [matches] 的区别在于它会查法定节假日与调休：
     *  - 工作日 = 法定工作日：调休补班的周六日照常提醒，放假的周一至周五不提醒
     *  - 周末   = 法定休息日：周末或法定假日
     *  - 每天 / 自定义星期：按字面星期匹配，可按 [skipHolidays] 跳过休息日
     *
     * 「法定节假日」按用户口径 = **周末 + 法定假日**（放假都不提醒），
     * 但调休补班日（本该周末却要上班，如 2026-10-10）算工作日，照常提醒。
     *
     * @param dateKey      形如 2026-10-01 的日期键
     * @param calendarDayOfWeek Calendar.DAY_OF_WEEK（1=周日 … 7=周六）
     */
    fun shouldTrigger(
        mask: Int,
        dateKey: String,
        calendarDayOfWeek: Int,
        skipHolidays: Boolean = true
    ): Boolean {
        // 休息日 = 周末 + 法定假日；调休补班日不在此列（那天要上班，照常提醒）
        val skipRest = skipHolidays && HolidayCalendar.isRestDay(dateKey)
        return when (mask) {
            DAILY -> !skipRest
            WEEKDAYS -> HolidayCalendar.isWorkday(dateKey)
            WEEKEND -> HolidayCalendar.isRestDay(dateKey)
            else -> matches(mask, calendarDayOfWeek) && !skipRest
        }
    }

    /**
     * 统一入口：这条任务在 dateKey 这天该不该提醒。
     * 按星期的走法定日历（shouldTrigger），按日期的走 isDateDue（房租这类账期不受调休影响）。
     */
    fun shouldTriggerTask(task: Task, dateKey: String, calendarDayOfWeek: Int): Boolean {
        // 通用结束日期：任何重复类型过了 endDate 都不再触发
        // （TYPE_DATE 的 isDateDue 内部还会再查一次，重复检查无害）
        if (task.endDate.isNotBlank() && dateKey > task.endDate) return false
        // 「重复时间段」的起点：按星期模式下 anchorDate 存完整日期（长度 10）时作为开始日，
        // 早于它不触发（v3.27 自定义期间起点；v3.35 起永久也记起点，「从开始时间起一直重复」）。
        // 老任务的 anchorDate 为空或农历 "MM-dd"——按建档日兜底，见 [weekdayStartKey]
        if (task.repeatType == TYPE_WEEKDAY && dateKey < weekdayStartKey(task)) return false
        return when (task.repeatType) {
            TYPE_ONCE -> task.anchorDate.isNotBlank() && dateKey == task.anchorDate
            TYPE_DATE -> isDateDue(
                task.anchorDate, task.intervalMonths, task.endDate, dateKey,
                task.intervalDays, dayMaskOf(task.repeatMask), task.monthsMask
            )
            TYPE_LUNAR -> isLunarDueFor(task, dateKey)
            else -> shouldTrigger(task.repeatMask, dateKey, calendarDayOfWeek, task.skipHolidays)
        }
    }

    /**
     * 农历每年判定（纯函数，便于单测）：anchorDate 是农历 "MM-dd"，
     * 公历触发日 = 农历日换算的公历日 − lunarLeadDays（"前一天提醒"就传 1）。
     * 腊月里的农历日会落到公历次年，所以当年与上一年都要换算。
     * [endDate] 非空时到期日超过它就不再触发（与 isDateDue 的结束日期同义）。
     * [intervalYears] > 1 时隔 N 年触发一次，锚年为 [baseYear]（农历年，
     * 由 lunarBaseYear 按建档时间算出）；腊月日期的农历年与公历年可能差一，过滤按农历年。
     */
    fun isLunarDue(
        anchorDate: String, leadDays: Int, dateKey: String,
        endDate: String = "", intervalYears: Int = 1, baseYear: Int = 0
    ): Boolean {
        val p = anchorDate.split("-")
        val m = p.getOrNull(0)?.toIntOrNull() ?: return false
        val d = p.getOrNull(1)?.toIntOrNull() ?: return false
        val y = dateKey.take(4).toIntOrNull() ?: return false
        val lead = leadDays.coerceAtLeast(0)
        val step = intervalYears.coerceAtLeast(1)
        val hit = listOf(y, y - 1).any { year ->
            (step <= 1 || baseYear <= 0 || (year - baseYear) % step == 0) &&
                com.norbury.taskalarm.util.LunarCalendar.lunarToSolar(year, m, d)
                    ?.minusDays(lead.toLong())?.toString() == dateKey
        }
        return hit && (endDate.isBlank() || dateKey <= endDate)
    }

    /**
     * 农历任务的到期判定（带任务上下文）：结束日期 + 间隔年（intervalMonths 复用为年数）+
     * 建档时间锚点。计划页 / 日历联动 / 触发调度统一走这个入口。
     */
    fun isLunarDueFor(task: Task, dateKey: String): Boolean {
        val p = task.anchorDate.split("-")
        val m = p.getOrNull(0)?.toIntOrNull() ?: return false
        val d = p.getOrNull(1)?.toIntOrNull() ?: return false
        return isLunarDue(
            task.anchorDate, task.lunarLeadDays, dateKey, task.endDate,
            task.intervalMonths, lunarBaseYear(m, d, task.createdAt)
        )
    }

    /** 农历「每 N 年」的锚年：建档日当天及之后第一个该农历日的农历年 */
    fun lunarBaseYear(month: Int, day: Int, createdMillis: Long): Int {
        val created = java.time.Instant.ofEpochMilli(createdMillis)
            .atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        for (y in created.year..2100) {
            val s = com.norbury.taskalarm.util.LunarCalendar.lunarToSolar(y, month, day)
                ?: continue
            if (!s.isBefore(created)) return y
        }
        return created.year
    }

    /**
     * 按日期判定：dateKey 是否落在「anchorDate 起每 intervalMonths 个月、锚点日那一天」。
     * [intervalDays] > 0 时改按天递推：锚点日起每 N 天触发。
     *
     * 规则：
     *  - 早于锚点日、晚于结束日 → 不触发
     *  - 月模式：目标月的「几号」按锚点日的日数；若当月天数不足（锚点 31 号遇 2 月）则落到当月最后一天，
     *    与锚点的月份差必须能被 interval 整除
     *  - 天模式：与锚点相差的天数能被 intervalDays 整除
     *
     * 独立于节假日：房租、账单这类账期不会因为调休顺延。
     */
    fun isDateDue(
        anchorDate: String,
        intervalMonths: Int,
        endDate: String,
        dateKey: String,
        intervalDays: Int = 0,
        dayMask: Int = 0,
        monthsMask: Int = 0
    ): Boolean {
        if (anchorDate.isBlank() || dateKey.isBlank()) return false
        if (dateKey < anchorDate) return false
        if (endDate.isNotBlank() && dateKey > endDate) return false

        val a = dateParts(anchorDate) ?: return false
        val d = dateParts(dateKey) ?: return false

        if (intervalDays > 0) {
            val diff = epochDay(d) - epochDay(a)
            return diff >= 0 && diff % intervalDays.coerceAtLeast(1) == 0L
        }

        val dim = daysInMonth(d.first, d.second)

        // 每年多选月份：只要日子落在所选月份即可，不参与「每 N 月」递推（v3.34.130）
        if (monthsMask != 0) {
            if ((monthsMask and (1 shl (d.second - 1))) == 0) return false
            if (dayMask != 0) {
                for (day in 1..31) {
                    if ((dayMask and (1 shl (day - 1))) != 0 && d.third == minOf(day, dim)) return true
                }
                return false
            }
            return d.third == minOf(a.third, dim)
        }

        val interval = intervalMonths.coerceAtLeast(1)
        val monthDiff = (d.first - a.first) * 12 + (d.second - a.second)
        if (monthDiff < 0 || monthDiff % interval != 0) return false
        // 多选日（每月/每年的「几号」）：任一所选日在该月的有效日命中即触发
        if (dayMask != 0) {
            for (day in 1..31) {
                if ((dayMask and (1 shl (day - 1))) != 0 && d.third == minOf(day, dim)) return true
            }
            return false
        }
        // 老行为：锚点日；当月天数不足时落到月末（31 号遇 2 月）
        return d.third == minOf(a.third, dim)
    }

    /** 一次性任务的描述，如「仅 2026-10-17 一次」 */
    fun describeOnce(dateKey: String): String =
        if (dateKey.isBlank()) "未设置日期" else "仅 $dateKey 一次"

    /** 人类可读的日期型重复描述，如「每 3 个月的 21 号 · 至 2027-07-21」 */
    fun describeDate(
        anchorDate: String,
        intervalMonths: Int,
        endDate: String,
        intervalDays: Int = 0,
        dayMask: Int = 0,
        monthsMask: Int = 0
    ): String {
        if (anchorDate.isBlank()) return "未设置日期"
        val base = if (intervalDays > 0) {
            "每 $intervalDays 天（自 $anchorDate）"
        } else {
            val days = if (dayMask != 0) daysOf(dayMask)
            else listOfNotNull(anchorDate.takeLast(2).toIntOrNull())
            if (days.isEmpty()) return "未设置日期"
            val dayText = days.joinToString("/")
            val months = if (monthsMask != 0) monthsOf(monthsMask)
            else listOfNotNull(anchorDate.substring(5, 7).toIntOrNull() ?: 1)
            val monthText = months.joinToString("/")
            when {
                // 每年只选月份 = 该月每天提醒（v3.34.126）
                intervalMonths == 12 && days.size == 31 -> "每年 $monthText 月整月"
                intervalMonths == 12 -> "每年 $monthText 月 $dayText 日"
                intervalMonths <= 1 -> "每月 $dayText 号"
                else -> "每 $intervalMonths 个月的 $dayText 号"
            }
        }
        return if (endDate.isBlank()) base else "$base · 至 $endDate"
    }

    /** yyyy-MM-dd → epoch day（java.time，minSdk 26 起可用） */
    private fun epochDay(p: Triple<Int, Int, Int>): Long =
        java.time.LocalDate.of(p.first, p.second, p.third).toEpochDay()

    /* ---- 日期工具（纯 JVM，便于单测） ---- */

    private fun dateParts(key: String): Triple<Int, Int, Int>? {
        val p = key.split("-")
        val y = p.getOrNull(0)?.toIntOrNull() ?: return null
        val m = p.getOrNull(1)?.toIntOrNull() ?: return null
        val d = p.getOrNull(2)?.toIntOrNull() ?: return null
        if (m !in 1..12 || d !in 1..31) return null
        return Triple(y, m, d)
    }

    private fun daysInMonth(year: Int, month: Int): Int {
        val cal = Calendar.getInstance()
        cal.clear()
        cal.set(year, month - 1, 1)
        return cal.getActualMaximum(Calendar.DAY_OF_MONTH)
    }

    fun describe(mask: Int): String = when (mask) {
        DAILY -> "每天"
        WEEKDAYS -> "工作日"
        WEEKEND -> "周末"
        else -> {
            val names = arrayOf("日", "一", "二", "三", "四", "五", "六")
            val sb = StringBuilder("周")
            for (i in 0..6) if ((mask and (1 shl i)) != 0) sb.append(names[i])
            if (sb.length == 1) "仅今天" else sb.toString()
        }
    }

    /**
     * 卡片信息行的重复缩写。**必须先按 repeatType 分流**——
     * TYPE_ONCE 的 repeatMask 是默认全选（DAILY），直接 describe(mask) 会显示成「每天」。
     */
    fun repeatShortLabel(t: Task): String = when (t.repeatType) {
        TYPE_ONCE -> "仅一次"
        TYPE_DATE -> when {
            t.intervalDays > 0 -> "每${t.intervalDays}天"
            t.intervalMonths == 12 -> {
                val months = if (t.monthsMask != 0) RepeatRule.monthsOf(t.monthsMask)
                else listOfNotNull(t.anchorDate.substring(5, 7).toIntOrNull() ?: 1)
                val monthText = months.joinToString("/")
                val dm = RepeatRule.dayMaskOf(t.repeatMask)
                if (dm != 0 && RepeatRule.daysOf(dm).size == 31) "每年${monthText}月整月"
                else {
                    val days = if (dm != 0) RepeatRule.daysOf(dm).joinToString("/")
                    else t.anchorDate.takeLast(2)
                    "每年${monthText}月${days}日"
                }
            }
            else -> {
                val dm = RepeatRule.dayMaskOf(t.repeatMask)
                val days = if (dm != 0) RepeatRule.daysOf(dm).joinToString("/")
                else t.anchorDate.takeLast(2)
                if (t.intervalMonths > 1) "每${t.intervalMonths}月${days}号" else "每月${days}号"
            }
        }
        TYPE_LUNAR -> {
            val lunar = com.norbury.taskalarm.util.LunarCalendar
            val p = t.anchorDate.split("-")
            val m = p.getOrNull(0)?.toIntOrNull() ?: 0
            val d = p.getOrNull(1)?.toIntOrNull() ?: 0
            val label = "农历${lunar.monthName(m).dropLast(1)}${lunar.dayName(d)}"
            if (t.lunarLeadDays > 0) "$label·提前${t.lunarLeadDays}天" else label
        }
        else -> describe(t.repeatMask)
    }
}

/**
 * 一条任务。所有时间字段均以「当日分钟数」表示，例如 20:00 = 1200。
 *
 * 通知时间点由三个字段推导：
 *   催促时间 = deadlineMin - preOffsetMin
 *   复查时间 = deadlineMin + postOffsetMin
 * 两个时间点触发时都会先查询当日是否已完成，未完成才响铃。
 */
@Entity(tableName = "tasks")
data class Task(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** 任务名，例如「单杠抬腿」 */
    val name: String,

    /** 目标数量，例如 60；为 0 表示只需打勾完成 */
    val targetCount: Int = 0,

    /** 当日已完成数量（支持部分完成） */
    val doneCount: Int = 0,

    /** 截止时间（当日分钟数），例如 20:00 -> 1200 */
    val deadlineMin: Int = 20 * 60,

    /**
     * 催促提前量（分钟）：
     *  - **-1 = 关闭**（不催促）
     *  - **0 = 准时**：与截止时间同时响（截止 9:00 就 9:00 响）
     *  - \>0 = 截止前 N 分钟响
     */
    val preOffsetMin: Int = 30,

    /**
     * 复查延后量（分钟）：
     *  - **-1 = 关闭**（不复查）
     *  - **0 = 准时**：截止时间当天同一时刻检查（配合催促即"只在截止点响一次"）
     *  - \>0 = 截止后 N 分钟再检查一次
     */
    val postOffsetMin: Int = 120,

    /** 重复掩码，见 [RepeatRule] */
    val repeatMask: Int = RepeatRule.DAILY,

    /** 是否启用 */
    val enabled: Boolean = true,

    /** 铃声 URI，空字符串表示用铃声通道默认闹钟音 */
    val soundUri: String = "",

    /** 排序用 */
    val sortOrder: Int = 0,

    /** 创建时间，用于界定连续打卡的计算起点 */
    val createdAt: Long = System.currentTimeMillis(),

    /** 是否已联动写入小米时钟 */
    val linkedToClock: Boolean = false,

    /** 是否已联动写入系统日历 */
    val linkedToCalendar: Boolean = false,

    /**
     * 法定节假日是否跳过提醒。
     * 对「每天」「自定义星期」生效；「工作日 / 周末」本身已按法定日历判定，不受此项影响。
     */
    val skipHolidays: Boolean = true,

    /** 标签，用于首页分组（如 运动 / 饮食 / 工作）；空串表示未分组 */
    val tag: String = "",

    /**
     * 优先级：0 = 高、1 = 中（默认）、2 = 低。
     * 值越小越靠前，排序就是整数升序；列表一律「先按优先级、同档再按时间」。
     */
    val priority: Int = PRIORITY_MID,

    /**
     * 任务内的清单，一行一项（换行分隔），空串表示没有清单。
     *
     * 存在的意义：一个时间点要办一串事（买东西、练一组动作）时，
     * 不必拆成多条任务 —— 否则同一时段会挂一串闹钟，此起彼伏地响。
     * 一条任务永远只有「催促 + 复查」两次响铃，清单多长都一样。
     * 例："牛奶 ×2\n鸡蛋\n鸡胸肉\n哑铃片 2×2kg"
     */
    val items: String = "",

    /**
     * 每组次数（逐组打卡用）：0 表示不分组、一次性勾选。
     * 例：目标 60 次、每组 10 次 → 卡片上记 6 组，做满自动算完成。
     * 百分比类任务可设 目标 100 + 每组 10，每按一次记 10%。
     */
    val repsPerSet: Int = 0,

    /** 重复类型：0=按星期掩码（[RepeatRule.TYPE_WEEKDAY]），1=按日期（[RepeatRule.TYPE_DATE]） */
    val repeatType: Int = RepeatRule.TYPE_WEEKDAY,

    /**
     * 按日期重复的锚点日（首次发生日期，yyyy-MM-dd）。
     * 其「日」就是每月几号；后续按 [intervalMonths] 递推。
     * 例：2026-07-21 + 每 3 个月 → 7/21、10/21、1/21、4/21……
     */
    val anchorDate: String = "",

    /** 按日期重复的间隔（月），至少 1；[intervalDays] > 0 时忽略此项 */
    val intervalMonths: Int = 1,

    /** 按日期重复的间隔（天），至少 1；0 表示不按天、用 [intervalMonths] */
    val intervalDays: Int = 0,

    /** 每年多选月份（bit0-11 = 1-12 月）；0 = 只用锚点日期的月（v3.34.130） */
    val monthsMask: Int = 0,

    /** 按日期重复的结束日期（含当天），空表示一直重复 */
    val endDate: String = "",

    /**
     * 法定休息日使用的截止时间（当日分钟数）；**-1 表示与主时间相同**。
     *
     * 「法定休息日」按国务院公布的放假安排判定（[HolidayCalendar]），不是简单的周六周日：
     *  - 放假的周一至周五 → 休息日
     *  - 调休补班的周六周日 → **工作日**（仍用主时间）
     * 例：工作日 19:00 提醒，法定休息日改成 10:00。
     */
    val restDayDeadlineMin: Int = -1,

    /**
     * 农历每年任务（[RepeatRule.TYPE_LUNAR]）提前几天提醒：0 = 当天，1 = 前一天。
     * 例：正月廿八生日、提前 1 天 + 目标 10:00 → 正月廿七（公历）10:00 响。
     */
    val lunarLeadDays: Int = 0,

    /**
     * 每日触发时刻表（Room v14）。空串 = 旧单时刻模式（用 [deadlineMin]）。
     * 格式见 [TimeSpec]：
     *  - 固定列表 `"480,1020"` → 8:00 和 17:00 各响一次
     *  - 间隔循环 `"480/60/1320"` → 8:00 起每 60 分响到 22:00
     */
    val timeSpec: String = "",

    /**
     * 逐时刻的「开始 / 复查」偏移表（Room v15）。空串 = 所有时刻统一用
     * [preOffsetMin] / [postOffsetMin]（老任务零迁移）。
     * 格式见 [TimeOffsets]：`"480:90/30,1020:120/60"`。
     *
     * v3.34.21 起：用户要求「每个时刻分别对应自己的开始/复查时间」——
     * 原来只有一个任务级偏移套在所有时刻上，11:25 和 17:25 会在同一个点被提醒。
     */
    val timeOffsets: String = "",

    /**
     * 择一提醒（Room v16，v3.34.96）：多时刻任务「确认任一个时刻即算当天完成」。
     *
     * 用例：晚饭 17:10（点外卖）/ 18:00（回家做）—— 17:10 确认了就不再响 18:00；
     * 不确认则 18:00 照常响。落库只存这个布尔，判定走 [allTimesConfirmed]。
     */
    val eitherMode: Boolean = false
) {
    /** 清单项列表（去掉空行） */
    val itemList: List<String>
        get() = items.split('\n').map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * 清单项（带组数）：每行文本约定为
     *   - `哑铃卧推`         → 一次性项（显示「完成」按钮）
     *   - `哑铃卧推|4`       → 4 组（显示 − / 4 组计数 / +）
     *   - `哑铃卧推|4|8`     → 4 组 × 每组 8 次（次数只作展示）
     * 组数不从名字里猜 —— 由导入/编辑时显式写清楚。
     */
    val itemSpecs: List<Item>
        get() = itemList.map { line ->
            val p = line.split('|')
            val sets = p.getOrNull(1)?.trim()?.toIntOrNull()?.coerceAtLeast(1) ?: 1
            val reps = p.getOrNull(2)?.trim()?.toIntOrNull() ?: 0
            Item(p[0].trim(), sets, reps)
        }

    /** 清单项：名字 + 目标组数 + 每组次数（0 = 未指定） */
    data class Item(val name: String, val sets: Int = 1, val reps: Int = 0)

    val preAlertMin: Int get() = deadlineMin - preOffsetMin
    val finalCheckMin: Int get() = deadlineMin + postOffsetMin

    /** 指定日期当天实际的截止时间 */
    fun deadlineOn(dateKey: String): Int =
        if (restDayDeadlineMin >= 0 && HolidayCalendar.isRestDay(dateKey)) restDayDeadlineMin
        else deadlineMin

    /** 指定日期当天实际的催促时刻（沿用同一提前量倒推） */
    fun preAlertOn(dateKey: String): Int = deadlineOn(dateKey) - preOffsetMin

    /** 指定日期当天实际的复查时刻 */
    fun finalCheckOn(dateKey: String): Int = deadlineOn(dateKey) + postOffsetMin

    /** 是否多时刻任务（编辑页/响铃页按它分流「逐时刻确认」的交互） */
    val isMultiTime: Boolean get() = timeSpec.isNotBlank() && timesOn("").size > 1

    /**
     * 指定日期当天的触发时刻列表（当日分钟数，升序）。
     * 新模型走 [TimeSpec]；旧单时刻任务走 [deadlineOn]（保留休息日变体时间）。
     */
    fun timesOn(dateKey: String): List<Int> =
        if (timeSpec.isBlank()) listOf(deadlineOn(dateKey))
        else TimeSpec.timesOfDay(timeSpec).ifEmpty { listOf(deadlineOn(dateKey)) }

    /**
     * 某个时刻的提前量（分钟）。优先取该时刻的单独配置，回落任务级 [preOffsetMin]。
     * 三态语义不变：-1 关闭 / 0 准时 / >0 提前 N 分钟。
     */
    fun preOffsetOf(timeMin: Int): Int =
        if (timeSpec.isBlank()) preOffsetMin
        else TimeOffsets.preOf(timeOffsets, timeMin, preOffsetMin)

    /** 某个时刻的延后量（分钟）；语义同 [preOffsetOf] */
    fun postOffsetOf(timeMin: Int): Int =
        if (timeSpec.isBlank()) postOffsetMin
        else TimeOffsets.postOf(timeOffsets, timeMin, postOffsetMin)

    /**
     * 某个时刻的催促（开始）时刻 = 目标 - 该时刻的提前量。
     * 时刻表模式下每个时刻各有自己的提前量（v3.34.21 起，Task.timeOffsets）。
     */
    fun preAlertOfTime(timeMin: Int): Int = timeMin - preOffsetOf(timeMin)

    /** 某个时刻的复查（截止）时刻 = 目标 + 该时刻的延后量 */
    fun finalCheckOfTime(timeMin: Int): Int = timeMin + postOffsetOf(timeMin)

    /**
     * 当天第一个还没确认的时刻下标（响铃页「我知道了」用；全确认过返回 -1）。
     */
    fun earliestUnconfirmed(record: TaskRecord?): Int {
        val times = timesOn("")
        if (times.size <= 1) return 0
        return times.indices.firstOrNull { !timeConfirmed(record, it) } ?: -1
    }

    /**
     * 是否至少有一个时刻开着催促（开始时间）。
     * 逐时刻偏移落地后，「开不开」是**每个时刻各自的判断**，不能只看任务级
     * preOffsetMin —— 那个值现在是没单独配置的时刻的默认值。
     */
    fun hasPreReminder(): Boolean = timesOn("").any { preOffsetOf(it) >= 0 }

    /** 是否至少有一个时刻开着复查（截止时间）；语义同 [hasPreReminder] */
    fun hasPostReminder(): Boolean = timesOn("").any { postOffsetOf(it) >= 0 }

    /** 多时刻任务：第 index 个时刻今天是否已确认（记录里 checkedItems 的 "t下标" 条目） */
    fun timeConfirmed(record: TaskRecord?, index: Int): Boolean =
        record?.confirmedTimes?.contains(index) == true

    /**
     * 多时刻任务：当天是否全部时刻都已确认（全确认才算当天完成）。
     *
     * **兼容老记录**（v3.34.21 之前写的）：那时多时刻任务只存了一个整体布尔
     * `completed`，没有 `t下标` 条目 —— 用户在首页点「完成」或勾完清单就会写成 true。
     * 这类记录一律视为「当天已完成」，否则升级后已完成的任务会集体变回未完成。
     * 只要有 `t` 条目（说明是逐时刻确认过的），就按新口径严格判。
     */
    fun allTimesConfirmed(record: TaskRecord?): Boolean {
        if (timeSpec.isBlank()) return record?.completed == true
        if (record == null) return false
        val confirmed = record.confirmedTimes
        if (confirmed.isEmpty()) return record.completed
        // 择一（v3.34.96）：确认任一个时刻即算当天完成 —— 后续时刻的闹钟会被跳过
        if (eitherMode) return true
        val n = timesOn("").size
        return n > 0 && confirmed.containsAll((0 until n).toList())
    }

    /**
     * 计划页展示用：这条任务实际占用的时间段「开始（催促）→ 目标」。
     * 用户口径：deadlineMin 是目标时间，复查那次响铃才是「截止」——复查不拉长展示段。
     * 没开催促就退化为单个时间点；催促跨过午夜时 formatMin 自动回绕。
     */
    fun spanOn(dateKey: String): String {
        val dl = deadlineOn(dateKey)
        return if (preOffsetMin > 0) "${formatMin(preAlertOn(dateKey))}–${formatMin(dl)}"
        else formatMin(dl)
    }

    /**
     * 从 dateKey（含）往后第一个到期日；找不到（还没开始 / 系列已结束）返回 null。
     */
    fun nextDueKeyAfter(dateKey: String, horizonDays: Int = 400): String? {
        if (repeatType != RepeatRule.TYPE_DATE || anchorDate.isBlank()) return null
        val p = dateKey.split("-")
        if (p.size < 3) return null
        val y = p[0].toIntOrNull() ?: return null
        val m = p[1].toIntOrNull() ?: return null
        val d = p[2].toIntOrNull() ?: return null
        val cal = Calendar.getInstance().apply {
            clear()
            set(y, m - 1, d)
        }
        repeat(horizonDays) {
            val key = AppDatabase.keyOf(cal.time)
            if (RepeatRule.isDateDue(
                    anchorDate, intervalMonths, endDate, key, intervalDays,
                    RepeatRule.dayMaskOf(repeatMask), monthsMask
                )
            ) {
                return key
            }
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return null
    }

    /**
     * 这次打卡该记在哪一天。
     *
     * 账期任务（房租、账单）**可以提前完成** —— 房东提前要就提前交。
     * 记录一律写到「本期到期日」而不是今天：这样到期那天不会再响，统计也认这一期；
     * 若记在今天，到期日照旧响一次、统计里那一期仍显示欠着。
     */
    fun recordKeyOn(dateKey: String): String {
        if (repeatType != RepeatRule.TYPE_DATE) return dateKey
        if (RepeatRule.isDateDue(
                anchorDate, intervalMonths, endDate, dateKey, intervalDays,
                RepeatRule.dayMaskOf(repeatMask), monthsMask
            )
        ) {
            return dateKey
        }
        return nextDueKeyAfter(dateKey) ?: dateKey
    }

    companion object {
        /** 高优先：最靠前 */
        const val PRIORITY_HIGH = 0

        /** 中优先：默认档 */
        const val PRIORITY_MID = 1

        /** 低优先：最靠后 */
        const val PRIORITY_LOW = 2

        /**
         * 催促 / 复查「关闭」的哨兵值。
         * 0 被腾出来表示「准时」（与截止同时响），所以关闭必须用 -1 表达 ——
         * 两个字段都为 -1 时这条任务没有任何响铃（导入前会在预览里给出警告）。
         */
        const val OFFSET_OFF = -1

        /** 滚轮一格对应的分钟数（5 分钟粒度，与时间选择器一致） */
        const val OFFSET_STEP = 5

        /**
         * 滚轮下标 → 分钟值：0 = 关闭(-1)，1 = 准时(0)，之后每格 5 分钟。
         * 编辑页的催促/复查滚轮与单测共用这一映射，保证两边永远一致。
         */
        fun offsetOfWheelIndex(which: Int): Int = when (which) {
            0 -> OFFSET_OFF
            1 -> 0
            else -> (which - 1) * OFFSET_STEP
        }

        /** 分钟值 → 滚轮下标（[offsetOfWheelIndex] 的互逆；非 5 倍数就近取格） */
        fun wheelIndexOfOffset(min: Int): Int = when {
            min < 0 -> 0
            min == 0 -> 1
            else -> ((min + OFFSET_STEP / 2) / OFFSET_STEP).coerceIn(1, 96) + 1
        }

        /** 优先级单字文案（卡片角标用） */
        fun priorityLabel(p: Int): String = when (p) {
            PRIORITY_HIGH -> "高"
            PRIORITY_LOW -> "低"
            else -> "中"
        }

        fun formatMin(min: Int): String {
            val m = ((min % 1440) + 1440) % 1440
            return "%02d:%02d".format(m / 60, m % 60)
        }

        /**
         * 补响宽限窗口（分钟）：闹钟该响却没响（App 被系统强杀、闹钟广播被拦）时，
         * 在错过时间后这个窗口内打开 App，会把今天漏掉的提醒补响一次。
         */
        const val CATCH_UP_GRACE_MIN = 120

        /**
         * 补响判定（纯函数，便于单测）：提醒应在 fireMin 触发，现在已到 nowMin
         * （均为当日分钟数）。迟到 1..CATCH_UP_GRACE_MIN 分钟 → 返回迟到分钟数；
         * 还没到点（nowMin <= fireMin，负数不回绕）或超出宽限 → null。
         */
        fun catchUpLateMin(fireMin: Int, nowMin: Int, graceMin: Int = CATCH_UP_GRACE_MIN): Int? {
            val late = nowMin - fireMin
            return if (late in 1..graceMin) late else null
        }
    }
}

/** 每日完成记录，用于统计与"忘做"追溯 */
@Entity(tableName = "records", primaryKeys = ["dateKey", "taskId"])
data class TaskRecord(
    /** 形如 2026-10-10 */
    val dateKey: String,
    val taskId: Long,
    val completed: Boolean,
    /** 完成后补记的完成数量 */
    val doneCount: Int = 0,
    /**
     * 清单里已勾选的下标，逗号分隔（如 "0,2"）。
     * 按天存：跨天自动重新开始，训练单每天重来一遍，购物单买完那天勾完即完成。
     */
    val checkedItems: String = "",
    /**
     * 清单里每项已完成几组，逗号分隔的 `下标:组数`（如 `"0:2,1:1"`）。
     * 与 [checkedItems] 并存：一次性项只勾（进 checkedItems），多组项记组数（进这里）。
     */
    val itemProgress: String = "",
    val updatedAt: Long = System.currentTimeMillis()
) {
    val checkedItemIndexes: Set<Int>
        get() = checkedItems.split(',').mapNotNull { it.trim().toIntOrNull() }.toSet()

    /**
     * 已确认的「每日时刻」下标（多时刻任务用）：checkedItems 里以 `t` 前缀的条目，
     * 如 "t0,t2" = 第 0、2 个时刻已确认。与清单下标（纯数字）互不干扰，一张字段两用。
     */
    val confirmedTimes: Set<Int>
        get() = checkedItems.split(',')
            .mapNotNull { it.trim().takeIf { s -> s.startsWith("t") }?.drop(1)?.toIntOrNull() }
            .toSet()

    /** 下标 → 已完成组数 */
    val itemProgressMap: Map<Int, Int>
        get() = itemProgress.split(',')
            .mapNotNull { seg ->
                val kv = seg.split(':')
                val i = kv.getOrNull(0)?.trim()?.toIntOrNull()
                val n = kv.getOrNull(1)?.trim()?.toIntOrNull()
                if (i != null && n != null) i to n else null
            }
            .toMap()

    /**
     * 合并清单勾选下标，**保留已确认的时刻条目**。
     *
     * v3.34.21 修 bug：以前各处写库（首页勾清单、逐组打卡、响铃页清单）
     * 都是 `checkedItems = next.joinToString(",")` 直接整串覆盖，
     * 把多时刻任务已经确认的 `"t下标"` 条目一起冲掉了 ——
     * 于是「响了一次 → 确认 → 之后随便勾一下清单」就把时刻状态清零。
     */
    fun mergeCheckedItems(next: Collection<Int>): TaskRecord {
        val parts = LinkedHashSet<String>()
        parts.addAll(next.map { it.toString() })
        parts.addAll(confirmedTimes.map { "t$it" })
        return copy(checkedItems = parts.sorted().joinToString(","))
    }

    /**
     * 去掉全部 `t` 条目（只留清单勾选），并把 completed 复位为假。
     * 用于「重置为今天未完成」—— 必须同时清 completed，
     * 否则 [allTimesConfirmed] 在「无 t 条目」时会回落读 completed，重置就白做了。
     */
    fun clearConfirmedTimes(): TaskRecord =
        copy(
            checkedItems = checkedItemIndexes.sorted().joinToString(","),
            completed = false
        )

    /** 多组项的进度串起来（供写库用） */
    fun progressString(map: Map<Int, Int>): String =
        map.filter { it.value > 0 }.toSortedMap()
            .entries.joinToString(",") { "${it.key}:${it.value}" }

    /**
     * 多时刻任务：登记第 index 个时刻已确认（写入 "t下标" 条目）。
     * 全部 [total] 个时刻都确认时 completed 置真（逐时刻确认定案：全确认才算当天完成）。
     * 清单勾选条目（纯数字）原样保留，一个字段两用互不干扰。
     */
    fun confirmTime(index: Int, total: Int): TaskRecord {
        val parts = checkedItems.split(',')
            .map { it.trim() }.filter { it.isNotEmpty() }.toMutableSet()
        parts.add("t${index.coerceAtLeast(0)}")
        val confirmedCount = parts.count { it.startsWith("t") }
        val all = total > 0 && confirmedCount >= total
        return copy(
            checkedItems = parts.sorted().joinToString(","),
            // 按已确认的时刻数重算，不做 `completed || all`：
            // 老记录的 completed 可能是「整体点过完成」留下的 true，
            // 那样 `|| all` 会让「只确认一个时刻」也保持完成 —— 正是要修的毛病
            completed = all,
            // 不能把「时刻数」写进 doneCount：带组数目标的多时刻任务全确认后，
            // 进度会被改成 2/60 这种（v3.34.117）
            updatedAt = System.currentTimeMillis()
        )
    }

    /**
     * 取消确认第 index 个时刻（v3.34.21：卡片上逐时刻可以反悔）。
     * 取消后如果不再「全部确认」，completed 要跟着退回来 —— 否则取消一个时刻
     * 却仍显示整天已完成，正是用户抱怨的那个毛病。
     */
    fun unconfirmTime(index: Int, total: Int): TaskRecord {
        val parts = checkedItems.split(',')
            .map { it.trim() }.filter { it.isNotEmpty() }.toMutableSet()
        parts.remove("t${index.coerceAtLeast(0)}")
        val remaining = parts.count { it.startsWith("t") }
        val all = total > 0 && remaining >= total
        return copy(
            checkedItems = parts.sorted().joinToString(","),
            completed = all,
            updatedAt = System.currentTimeMillis()
        )
    }
}
