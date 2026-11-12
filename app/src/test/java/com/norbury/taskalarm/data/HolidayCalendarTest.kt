package com.norbury.taskalarm.data

import com.norbury.taskalarm.util.HolidayCalendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * 法定节假日与调休的判定测试。
 *
 * 依据：国务院办公厅《关于 2026 年部分节假日安排的通知》（2025-11-04 发布）。
 * 这些用例直接跑生产代码，覆盖「放假的周一至周五」「调休补班的周末」两种情况。
 */
class HolidayCalendarTest {

    private fun dowOf(key: String): Int {
        val p = key.split("-").map { it.toInt() }
        return Calendar.getInstance().apply { set(p[0], p[1] - 1, p[2]) }
            .get(Calendar.DAY_OF_WEEK)
    }

    @Test
    fun `国庆假期内的周一至周五是节假日`() {
        // 10/1(四) ~ 10/7(三) 放假
        for (d in 1..7) {
            val key = "2026-10-%02d".format(d)
            assertEquals("$key 应为节假日", HolidayCalendar.DayType.HOLIDAY, HolidayCalendar.typeOf(key))
        }
    }

    @Test
    fun `国庆放假的周一至周五不再按工作日提醒`() {
        // 10/5(一)、10/6(二)、10/7(三) 名义上是工作日，但在放假区间内
        for (key in listOf("2026-10-05", "2026-10-06", "2026-10-07")) {
            assertFalse(
                "$key 放假，不应触发",
                RepeatRule.shouldTrigger(RepeatRule.WEEKDAYS, key, dowOf(key))
            )
        }
    }

    @Test
    fun `调休补班的周末按工作日提醒`() {
        // 10/10(六) 是国庆调休补班日
        val key = "2026-10-10"
        assertEquals(Calendar.SATURDAY, dowOf(key))
        assertEquals(HolidayCalendar.DayType.MAKEUP_WORKDAY, HolidayCalendar.typeOf(key))
        assertTrue(
            "调休补班的周六应触发工作日任务",
            RepeatRule.shouldTrigger(RepeatRule.WEEKDAYS, key, dowOf(key))
        )
    }

    @Test
    fun `全部调休补班日都被识别`() {
        val makeup = listOf(
            "2026-01-04",  // 元旦调休 周日
            "2026-02-14",  // 春节调休 周六
            "2026-02-28",  // 春节调休 周六
            "2026-05-09",  // 劳动节调休 周六
            "2026-09-20",  // 国庆调休 周日
            "2026-10-10"   // 国庆调休 周六
        )
        makeup.forEach {
            assertEquals("$it 应为调休补班", HolidayCalendar.DayType.MAKEUP_WORKDAY, HolidayCalendar.typeOf(it))
            assertTrue("$it 应算工作日", HolidayCalendar.isWorkday(it))
        }
    }

    @Test
    fun `春节长假整段放假`() {
        // 2/15(日) ~ 2/23(一)，共 9 天
        var count = 0
        for (d in 15..23) {
            val key = "2026-02-%02d".format(d)
            if (HolidayCalendar.typeOf(key) == HolidayCalendar.DayType.HOLIDAY) count++
        }
        assertEquals(9, count)
    }

    @Test
    fun `普通工作日与周末判定正确`() {
        assertEquals(HolidayCalendar.DayType.WORKDAY, HolidayCalendar.typeOf("2026-10-13"))  // 周二
        assertEquals(HolidayCalendar.DayType.WEEKEND, HolidayCalendar.typeOf("2026-10-11"))  // 周日
        assertTrue(RepeatRule.shouldTrigger(RepeatRule.WEEKDAYS, "2026-10-13", dowOf("2026-10-13")))
        assertFalse(RepeatRule.shouldTrigger(RepeatRule.WEEKDAYS, "2026-10-11", dowOf("2026-10-11")))
    }

    @Test
    fun `周末规则按法定休息日`() {
        // 周六放假 -> 算休息日 -> 触发
        assertTrue(RepeatRule.shouldTrigger(RepeatRule.WEEKEND, "2026-10-03", dowOf("2026-10-03")))
        // 周六调休补班 -> 不算休息日 -> 不触发
        assertFalse(RepeatRule.shouldTrigger(RepeatRule.WEEKEND, "2026-10-10", dowOf("2026-10-10")))
    }

    @Test
    fun `每天与自定义星期受节假日开关控制`() {
        val holiday = "2026-10-02"                 // 国庆放假的周五
        val custom = (1 shl 5)                     // 周五（Calendar.FRIDAY=6 -> 位 5）
        // 开：跳过
        assertFalse(RepeatRule.shouldTrigger(custom, holiday, dowOf(holiday), skipHolidays = true))
        // 关：照常
        assertTrue(RepeatRule.shouldTrigger(custom, holiday, dowOf(holiday), skipHolidays = false))
        // 每天同理
        assertFalse(RepeatRule.shouldTrigger(RepeatRule.DAILY, holiday, dowOf(holiday), skipHolidays = true))
        assertTrue(RepeatRule.shouldTrigger(RepeatRule.DAILY, holiday, dowOf(holiday), skipHolidays = false))
    }

    @Test
    fun `放假总天数与已覆盖年份`() {
        // 元旦3 + 春节9 + 清明3 + 劳动节5 + 端午3 + 中秋3 + 国庆7 = 33 天
        var holidays = 0
        for (m in 1..12) {
            for (d in 1..31) {
                val key = "2026-%02d-%02d".format(m, d)
                runCatching {
                    if (HolidayCalendar.typeOf(key) == HolidayCalendar.DayType.HOLIDAY) holidays++
                }
            }
        }
        assertEquals(33, holidays)
        assertTrue(HolidayCalendar.coveredYears().contains(2026))
        assertTrue(HolidayCalendar.isCovered("2026-10-10"))
        assertFalse(HolidayCalendar.isCovered("2030-01-01"))
    }

    @Test
    fun `2027 年放假与调休按国办通知`() {
        // 国办发明电〔2026〕10 号：元旦3 + 春节9 + 清明3 + 劳动节5 + 端午1 + 中秋1 + 国庆7 = 29 天
        var holidays = 0
        for (m in 1..12) {
            for (d in 1..31) {
                val key = "2027-%02d-%02d".format(m, d)
                runCatching {
                    if (HolidayCalendar.typeOf(key) == HolidayCalendar.DayType.HOLIDAY) holidays++
                }
            }
        }
        assertEquals(29, holidays)

        val makeup = listOf(
            "2027-01-31",  // 春节调休 周日
            "2027-02-14",  // 春节调休 周日
            "2027-05-08",  // 劳动节调休 周六
            "2027-09-26",  // 国庆调休 周日
            "2027-10-09"   // 国庆调休 周六
        )
        makeup.forEach {
            assertEquals("$it 应为调休补班", HolidayCalendar.DayType.MAKEUP_WORKDAY, HolidayCalendar.typeOf(it))
            assertTrue("$it 应算工作日", HolidayCalendar.isWorkday(it))
        }

        assertTrue(HolidayCalendar.isCovered("2027-02-10"))
        // 春节放假的周五不触发工作日任务；补班的周日反而触发
        assertFalse(RepeatRule.shouldTrigger(RepeatRule.WEEKDAYS, "2027-02-12", dowOf("2027-02-12")))
        assertTrue(RepeatRule.shouldTrigger(RepeatRule.WEEKDAYS, "2027-01-31", dowOf("2027-01-31")))
    }

    @Test
    fun `未覆盖年份回退为周一至周五`() {
        // 2030 年未内置数据：周一照常触发，周末不触发
        val monday = "2030-09-02"
        val saturday = "2030-09-07"
        assertTrue(RepeatRule.shouldTrigger(RepeatRule.WEEKDAYS, monday, dowOf(monday)))
        assertFalse(RepeatRule.shouldTrigger(RepeatRule.WEEKDAYS, saturday, dowOf(saturday)))
    }
}
