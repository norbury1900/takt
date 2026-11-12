package com.norbury.taskalarm.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用户的真实房租场景：
 *   2026-10-21 交第一次 → 之后每 3 个月交一次 → 到 2027-07-21 为止。
 * 期望命中的日子：2026-10-21、2027-01-21、2027-04-21、2027-07-21（共 4 次）。
 */
class RentOctTest {

    private val anchor = "2026-10-21"
    private val end = "2027-07-21"

    private fun due(key: String) = RepeatRule.isDateDue(anchor, 3, end, key)

    @Test
    fun 四个交租日全部命中() {
        listOf("2026-10-21", "2027-01-21", "2027-04-21", "2027-07-21").forEach {
            assertTrue("$it 应该提醒", due(it))
        }
    }

    @Test
    fun 非交租月不提醒() {
        listOf(
            "2026-11-21", "2026-12-21",
            "2027-02-21", "2027-03-21", "2027-05-21", "2027-06-21"
        ).forEach { assertFalse("$it 不该提醒", due(it)) }
    }

    @Test
    fun 锚点之前与结束日之后都不提醒() {
        assertFalse(due("2026-09-21"))
        assertFalse(due("2027-08-21"))
        assertFalse(due("2027-10-21"))   // 即便月份差是 12（能被 3 整除），也已被结束日截断
    }

    @Test
    fun 同一个月里只有21号提醒() {
        assertFalse(due("2026-10-20"))
        assertTrue(due("2026-10-21"))
        assertFalse(due("2026-10-22"))
    }

    @Test
    fun 任务级分发走日期分支() {
        val rent = Task(
            name = "交房租",
            repeatType = RepeatRule.TYPE_DATE,
            anchorDate = anchor,
            intervalMonths = 3,
            endDate = end
        )
        assertTrue(RepeatRule.shouldTriggerTask(rent, "2027-01-21", 4))
        assertFalse(RepeatRule.shouldTriggerTask(rent, "2027-02-21", 1))
        assertFalse(RepeatRule.shouldTriggerTask(rent, "2027-10-21", 4))
    }

    @Test
    fun 账期不受法定节假日调休影响() {
        // 2027-01-21 若是工作日/周末都要提醒；这里直接用"当天该提醒"验证不查节假日表
        val rent = Task(
            name = "交房租",
            repeatType = RepeatRule.TYPE_DATE,
            anchorDate = anchor,
            intervalMonths = 3,
            endDate = end,
            skipHolidays = false
        )
        assertTrue(RepeatRule.shouldTriggerTask(rent, "2027-01-21", 4))
    }

    @Test
    fun 描述文案正确() {
        assertEquals(
            "每 3 个月的 21 号 · 至 2027-07-21",
            RepeatRule.describeDate(anchor, 3, end)
        )
    }
}
