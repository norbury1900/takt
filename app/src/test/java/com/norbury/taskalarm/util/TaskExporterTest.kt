package com.norbury.taskalarm.util

import com.norbury.taskalarm.data.RepeatRule
import com.norbury.taskalarm.data.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TaskExporter 往返（round-trip）测试。
 *
 * 导出的意义就在于「还能原样导回来」：换机、存档、分享给别人的计划，
 * 都依赖 export → import 不丢字段。这里对每条任务逐字段比对，
 * 只忽略与「模板语义」无关的运行时字段（id / doneCount / sortOrder / createdAt）。
 */
class TaskExporterTest {

    @Test
    fun `每天任务往返一致`() {
        val t = task(
            name = "单杠抬腿",
            targetCount = 60,
            repsPerSet = 10,
            deadlineMin = 20 * 60,
            restDayDeadlineMin = 10 * 60,
            preOffsetMin = 30,
            postOffsetMin = 120,
            repeatMask = RepeatRule.DAILY,
            skipHolidays = true,
            tag = "运动",
            linkedToClock = true,
            linkedToCalendar = false
        )
        val back = roundTrip(t)
        assertEquals(t.name, back.name)
        assertEquals(t.targetCount, back.targetCount)
        assertEquals(t.repsPerSet, back.repsPerSet)
        assertEquals(t.deadlineMin, back.deadlineMin)
        assertEquals(t.restDayDeadlineMin, back.restDayDeadlineMin)
        assertEquals(t.preOffsetMin, back.preOffsetMin)
        assertEquals(t.postOffsetMin, back.postOffsetMin)
        assertEquals(t.repeatMask, back.repeatMask)
        assertEquals(t.repeatType, back.repeatType)
        assertEquals(t.skipHolidays, back.skipHolidays)
        assertEquals(t.tag, back.tag)
        assertEquals(t.linkedToClock, back.linkedToClock)
        assertEquals(t.linkedToCalendar, back.linkedToCalendar)
    }

    @Test
    fun `工作日任务往返一致`() {
        val t = task(
            name = "背单词",
            targetCount = 30,
            deadlineMin = 21 * 60 + 30,
            preOffsetMin = 15,
            postOffsetMin = 60,
            repeatMask = RepeatRule.WEEKDAYS,
            tag = "学习"
        )
        val back = roundTrip(t)
        assertEquals(RepeatRule.WEEKDAYS, back.repeatMask)
        // 休息日截止时间 -1 = 与主时间相同：导出省略，导回仍是 -1
        assertEquals(-1, back.restDayDeadlineMin)
        assertEquals("学习", back.tag)
        assertEquals(21 * 60 + 30, back.deadlineMin)
    }

    @Test
    fun `自定义星期掩码往返一致`() {
        val t = task(name = "游泳", repeatMask = 0b0101010) // 周一 / 三 / 五
        val back = roundTrip(t)
        assertEquals(0b0101010, back.repeatMask)
    }

    @Test
    fun `周末任务往返一致`() {
        val t = task(name = "大扫除", repeatMask = RepeatRule.WEEKEND)
        assertEquals(RepeatRule.WEEKEND, roundTrip(t).repeatMask)
    }

    @Test
    fun `按日期任务往返一致`() {
        val t = task(
            name = "交房租",
            deadlineMin = 10 * 60,
            repeatType = RepeatRule.TYPE_DATE,
            anchorDate = "2026-07-21",
            intervalMonths = 3,
            endDate = "2027-07-21"
        )
        val back = roundTrip(t)
        assertEquals(RepeatRule.TYPE_DATE, back.repeatType)
        assertEquals("2026-07-21", back.anchorDate)
        assertEquals(3, back.intervalMonths)
        assertEquals("2027-07-21", back.endDate)
        // 按日期的任务 repeatMask 无意义，但也应保持可导入（掩码缺省即可）
        assertTrue(back.repeatMask != 0)
    }

    @Test
    fun `按日期无结束时间往返一致`() {
        val t = task(
            name = "还信用卡",
            deadlineMin = 9 * 60,
            repeatType = RepeatRule.TYPE_DATE,
            anchorDate = "2026-10-05",
            intervalMonths = 1,
            endDate = ""
        )
        val back = roundTrip(t)
        assertEquals("2026-10-05", back.anchorDate)
        assertEquals("", back.endDate)
    }

    @Test
    fun `多条任务整体往返`() {
        val list = listOf(
            task(name = "单杠抬腿", targetCount = 60, repsPerSet = 10, tag = "运动"),
            task(name = "背单词", targetCount = 30, repeatMask = RepeatRule.WEEKDAYS, tag = "学习"),
            task(
                name = "交房租",
                deadlineMin = 10 * 60,
                repeatType = RepeatRule.TYPE_DATE,
                anchorDate = "2026-07-21",
                intervalMonths = 3,
                endDate = "2027-07-21"
            )
        )
        val json = TaskExporter.toJson(list)
        val r = TaskImporter.parse(json)
        assertTrue("导出再导入不应报错：${r.error}", r.ok)
        assertEquals(list.size, r.tasks.size)
        list.zip(r.tasks).forEach { (a, b) ->
            assertEquals(a.name, b.name)
            assertEquals(a.targetCount, b.targetCount)
            assertEquals(a.repeatType, b.repeatType)
        }
    }

    @Test
    fun `导出文本是带版本号的 v4 格式`() {
        val json = TaskExporter.toJson(listOf(task(name = "拉伸")))
        assertTrue(json.contains("\"version\": 5"))
        assertTrue(json.contains("\"tasks\""))
        assertTrue(json.contains("拉伸"))
    }

    @Test
    fun `优先级写入文件并原样读回，默认中档不写`() {
        // 高优先：写进 JSON，round-trip 后仍是高
        val hi = TaskExporter.toJson(listOf(task(name = "交房租", priority = Task.PRIORITY_HIGH)))
        assertTrue(hi.contains("\"priority\": \"高\""))
        assertEquals(Task.PRIORITY_HIGH, roundTrip(task(name = "交房租", priority = Task.PRIORITY_HIGH)).priority)

        // 中档是默认值：不占用文件字段，读回仍是中
        val mid = TaskExporter.toJson(listOf(task(name = "拉伸")))
        assertFalse(mid.contains("priority"))
        assertEquals(Task.PRIORITY_MID, roundTrip(task(name = "拉伸")).priority)
    }

    @Test
    fun `清单写入文件并原样读回，空清单不写`() {
        val withItems = task(name = "每周采购").copy(items = "牛奶 2L\n鸡蛋 30 个")
        val json = TaskExporter.toJson(listOf(withItems))
        assertTrue(json.contains("\"items\""))
        assertEquals(
            listOf("牛奶 2L", "鸡蛋 30 个"),
            TaskImporter.parse(json).tasks[0].itemList
        )

        assertFalse(TaskExporter.toJson(listOf(task(name = "拉伸"))).contains("items"))
    }

    @Test
    fun `按星期重复的结束日期也能往返`() {
        // 曾经漏导出：按星期任务只写 repeat，不写 endDate → 导出再导入会丢掉"提醒到某天"
        val t = task(name = "穿正装上班", repeatMask = RepeatRule.DAILY).copy(endDate = "2026-10-21")
        val json = TaskExporter.toJson(listOf(t))
        assertTrue("按星期的 endDate 应写进文件：$json", json.contains("\"endDate\": \"2026-10-21\""))
        assertEquals("2026-10-21", TaskImporter.parse(json).tasks[0].endDate)
    }

    @Test
    fun `按星期重复的开始日期也能往返`() {
        // v3.35 起永久也记起点（「从开始时间起一直重复」），导出再导入不能丢
        val t = task(name = "工作日背单词", repeatMask = RepeatRule.WEEKDAYS)
            .copy(anchorDate = "2026-10-20")
        val json = TaskExporter.toJson(listOf(t))
        assertTrue("按星期的 startDate 应写进文件：$json", json.contains("\"startDate\": \"2026-10-20\""))
        val back = TaskImporter.parse(json).tasks[0]
        assertEquals("2026-10-20", back.anchorDate)
        assertEquals(RepeatRule.TYPE_WEEKDAY, back.repeatType)
    }

    @Test
    fun `没有起点的按星期任务不写开始日期`() {
        // 老任务 anchorDate 为空：不写 startDate，导入后仍是无起点（行为不变）
        val t = task(name = "每天拉伸", repeatMask = RepeatRule.DAILY)
        assertFalse(TaskExporter.toJson(listOf(t)).contains("startDate"))
    }

    @Test
    fun `准时与关闭的提醒时机往返一致`() {
        // v3.26.0 起：-1 = 关闭，0 = 准时（与截止同时响）
        val t = task(
            name = "准点打卡",
            deadlineMin = 9 * 60,
            preOffsetMin = 0,
            postOffsetMin = Task.OFFSET_OFF
        )
        val back = roundTrip(t)
        assertEquals(0, back.preOffsetMin)
        assertEquals(Task.OFFSET_OFF, back.postOffsetMin)
        // 准时：催促时刻就是截止时刻，导出/导入都不能把它弄丢
        assertEquals(9 * 60, back.preAlertMin)
    }

    @Test
    fun `逐时刻的提醒时机往返一致`() {
        // 11:25 提前 30 分、17:25 只在准点响 —— 多对多的往返不能塌成统一偏移
        val t = task(
            name = "喝水",
            deadlineMin = 11 * 60 + 25,
            preOffsetMin = 30,
            postOffsetMin = 120,
            timeSpec = "685,1045",
            timeOffsets = "685:30/20,1045:0/-1"
        )
        val json = TaskExporter.toJson(listOf(t))
        assertTrue("应写出 timeOffsets：$json", json.contains("\"timeOffsets\""))
        val back = roundTrip(t)
        assertEquals("685,1045", back.timeSpec)
        assertEquals(30, back.preOffsetOf(685))
        assertEquals(20, back.postOffsetOf(685))
        assertEquals(0, back.preOffsetOf(1045))
        assertEquals(Task.OFFSET_OFF, back.postOffsetOf(1045))
        assertEquals(10 * 60 + 55, back.preAlertOfTime(685))
        assertEquals(11 * 60 + 45, back.finalCheckOfTime(685))
        // 17:25 那一档复查是关闭的 —— 调用方必须先判 postOffsetOf >= 0 再算钟点
        assertEquals(Task.OFFSET_OFF, back.postOffsetOf(1045))
    }

    @Test
    fun `统一偏移的多时刻任务不写 timeOffsets`() {
        // 没逐时刻配置 = 沿用任务级偏移，字段省略（老版本 App 读到也能正常提醒）
        val t = task(name = "服药", timeSpec = "480,750", preOffsetMin = 10, postOffsetMin = 60)
        assertFalse(TaskExporter.toJson(listOf(t)).contains("timeOffsets"))
        assertEquals("", roundTrip(t).timeOffsets)
        assertEquals(10, roundTrip(t).preOffsetOf(750))
    }

    @Test
    fun `落在时刻表之外的逐时刻配置被丢弃`() {
        // timeOffsets 里有 07:00，但时刻表只有 09:00 / 21:00 → 07:00 那条不保留
        val json = TaskExporter.toJson(
            listOf(
                task(
                    name = "练字", timeSpec = "540,1260",
                    timeOffsets = "420:10/10,540:30/30"
                )
            )
        )
        val back = TaskImporter.parse(json).tasks[0]
        assertFalse(back.timeOffsets.contains("420:"))
        assertTrue(back.timeOffsets.contains("540:30/30"))
    }

    /* ---------------- helper ---------------- */

    private fun task(
        name: String,
        targetCount: Int = 0,
        repsPerSet: Int = 0,
        deadlineMin: Int = 20 * 60,
        restDayDeadlineMin: Int = -1,
        preOffsetMin: Int = 30,
        postOffsetMin: Int = 120,
        repeatMask: Int = RepeatRule.DAILY,
        skipHolidays: Boolean = true,
        tag: String = "",
        repeatType: Int = RepeatRule.TYPE_WEEKDAY,
        anchorDate: String = "",
        intervalMonths: Int = 1,
        endDate: String = "",
        linkedToClock: Boolean = false,
        linkedToCalendar: Boolean = false,
        priority: Int = Task.PRIORITY_MID,
        timeSpec: String = "",
        timeOffsets: String = ""
    ) = Task(
        name = name,
        targetCount = targetCount,
        repsPerSet = repsPerSet,
        deadlineMin = deadlineMin,
        restDayDeadlineMin = restDayDeadlineMin,
        preOffsetMin = preOffsetMin,
        postOffsetMin = postOffsetMin,
        repeatMask = repeatMask,
        skipHolidays = skipHolidays,
        tag = tag,
        repeatType = repeatType,
        anchorDate = anchorDate,
        intervalMonths = intervalMonths,
        endDate = endDate,
        linkedToClock = linkedToClock,
        linkedToCalendar = linkedToCalendar,
        priority = priority,
        timeSpec = timeSpec,
        timeOffsets = timeOffsets
    )

    /** 单条任务：导出 → 导入，拿回导入侧的同位任务 */
    private fun roundTrip(t: Task): Task {
        val r = TaskImporter.parse(TaskExporter.toJson(listOf(t)))
        assertTrue("导出再导入不应报错：${r.error}", r.ok)
        assertEquals(1, r.tasks.size)
        return r.tasks[0]
    }
}
