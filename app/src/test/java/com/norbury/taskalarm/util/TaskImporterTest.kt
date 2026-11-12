package com.norbury.taskalarm.util

import com.norbury.taskalarm.data.RepeatRule
import com.norbury.taskalarm.data.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * TaskImporter 容错解析测试。
 *
 * 目的是覆盖"不同来源生成的训练计划"的写法差异：中英文字段名、
 * 各种时间写法、重复规则的多种表达方式等。这些用例直接跑生产代码。
 */
class TaskImporterTest {

    @Test
    fun `一次性任务只在那一天提醒`() {
        // 写法一：repeat 写 "once" + 顶层 date
        val r = TaskImporter.parse("""{"tasks":[{"name":"买哑铃","repeat":"once","date":"2026-10-17"}]}""")
        val t = r.tasks[0]
        assertEquals(RepeatRule.TYPE_ONCE, t.repeatType)
        assertEquals("2026-10-17", t.anchorDate)
        // 那天响，第二天不响（购物这种任务不该每天/每周循环）
        assertTrue(RepeatRule.shouldTriggerTask(t, "2026-10-17", 6))
        assertFalse(RepeatRule.shouldTriggerTask(t, "2026-10-18", 7))

        // 写法二：repeat 写成对象 {"type":"once","date":...}
        val r2 = TaskImporter.parse(
            """{"tasks":[{"name":"办证","repeat":{"type":"once","date":"2026-11-01"}}]}"""
        )
        assertEquals(RepeatRule.TYPE_ONCE, r2.tasks[0].repeatType)
        assertEquals("2026-11-01", r2.tasks[0].anchorDate)
    }

    @Test
    fun `提醒时机负一表示关闭零表示准时`() {
        // v3.26.0 起：-1 = 关闭，0 = 准时（与截止同时响），>0 = 提前/延后分钟数
        val r = TaskImporter.parse(
            """{"tasks":[{"name":"收快递","deadline":"16:00","urgeBefore":-1,"recheckAfter":0}]}"""
        )
        val t = r.tasks[0]
        assertEquals(Task.OFFSET_OFF, t.preOffsetMin)
        assertEquals(0, t.postOffsetMin)
        // 0 = 准时：复查时刻就是截止时刻
        assertEquals(16 * 60, t.finalCheckMin)

        // 整个字段写成字符串时也认（AI 生成端偶尔这么干）
        val r2 = TaskImporter.parse(
            """{"tasks":[{"name":"取件","deadline":"16:00","urgeBefore":"-1","recheckAfter":"0"}]}"""
        )
        assertEquals(-1, r2.tasks[0].preOffsetMin)
        assertEquals(0, r2.tasks[0].postOffsetMin)
    }

    @Test
    fun `标准模板可解析`() {
        val r = TaskImporter.parse(TaskImporter.TEMPLATE)
        assertNotNull("标准模板不应报错：${r.error}", r.ok)
        assertTrue(r.ok)
        // 模板就是「批量」的示范：tasks 数组里放多条
        assertEquals(7, r.tasks.size)
        val t = r.tasks[0]
        assertEquals("单杠抬腿", t.name)
        assertEquals(60, t.targetCount)
        assertEquals(20 * 60, t.deadlineMin)
        assertEquals(19 * 60 + 30, t.preAlertMin)
        assertEquals(22 * 60, t.finalCheckMin)
        assertEquals(RepeatRule.DAILY, t.repeatMask)
        // 模板是 v7 格式：v2~v7 的新字段都应落在任务上
        assertTrue(TaskImporter.TEMPLATE.contains("\"version\": 7"))
        assertEquals(10, t.repsPerSet)
        assertTrue(t.skipHolidays)
        assertEquals("运动", t.tag)
        assertEquals(Task.PRIORITY_HIGH, t.priority)
        // 第二条：仅工作日的学习任务，验证多任务各自独立解析
        val t2 = r.tasks[1]
        assertEquals("背单词", t2.name)
        assertEquals(30, t2.targetCount)
        assertEquals(21 * 60 + 30, t2.deadlineMin)
        assertEquals(RepeatRule.WEEKDAYS, t2.repeatMask)
        assertEquals("学习", t2.tag)
        assertEquals(0, t2.repsPerSet)
        // 第三条：带清单的采购任务（v4 新字段 items）
        val t3 = r.tasks[2]
        assertEquals("每周采购", t3.name)
        assertEquals(listOf("牛奶 2L", "鸡蛋 30 个", "鸡胸肉 1kg"), t3.itemList)
        // 第四条：固定多时刻（v5 times 数组），deadline 同步取第一个时刻
        val t4 = r.tasks[3]
        assertEquals("服药提醒", t4.name)
        assertEquals("480,750,1140", t4.timeSpec)
        assertEquals(8 * 60, t4.deadlineMin)
        // 第五条：间隔循环（v5 times 对象：9:00 起每 120 分响到 21:00）
        val t5 = r.tasks[4]
        assertEquals("喝水提醒", t5.name)
        assertEquals("540/120/1260", t5.timeSpec)
        assertEquals("", t5.timeOffsets)
        // 第六条：逐时刻的开始 / 复查（v7 timeOffsets 数组）—— 多对多的契约落点
        val t6 = r.tasks[5]
        assertEquals("练字", t6.name)
        assertEquals("540,1260", t6.timeSpec)
        assertEquals(30, t6.preOffsetOf(540))
        assertEquals(0, t6.postOffsetOf(540))
        assertEquals(Task.OFFSET_OFF, t6.preOffsetOf(1260))
        assertEquals(Task.OFFSET_OFF, t6.postOffsetOf(1260))
        // 顶层的 15 / 60 在这里被逐时刻覆盖，不是默认值了
        assertEquals(15, t6.preOffsetMin)
    }

    @Test
    fun `v1 旧格式文件照常导入`() {
        // version 不参与校验；缺的新字段走缺省值，保证老文件不失效
        val json = """{"version":1,"tasks":[{"name":"单杠抬腿","targetCount":60,"deadline":"20:00","repeat":"daily"}]}"""
        val t = single(json)
        assertEquals("单杠抬腿", t.name)
        assertEquals(60, t.targetCount)
        assertEquals(20 * 60, t.deadlineMin)
        // v1 没有的字段：默认跳过开启、无分组、一次性勾选
        assertTrue(t.skipHolidays)
        assertEquals("", t.tag)
        assertEquals(0, t.repsPerSet)
        // 完全不写 version 也行
        val noVer = single("""{"tasks":[{"name":"拉伸","deadline":"21:00"}]}""")
        assertEquals("拉伸", noVer.name)
    }

    @Test
    fun `中文字段名可解析`() {
        val json = """{"tasks":[{
            "任务":"深蹲","次数":30,"时间":"20:30","提前":10,"复查":60,"重复":"工作日"
        }]}"""
        val t = single(json)
        assertEquals("深蹲", t.name)
        assertEquals(30, t.targetCount)
        assertEquals(20 * 60 + 30, t.deadlineMin)
        assertEquals(10, t.preOffsetMin)
        assertEquals(60, t.postOffsetMin)
        assertEquals(RepeatRule.WEEKDAYS, t.repeatMask)
    }

    @Test
    fun `英文字段别名可解析`() {
        val json = """{"tasks":[{
            "title":"Pull up","reps":15,"due":"19:00","remindBefore":20,"recheckAfter":90,"schedule":"daily"
        }]}"""
        val t = single(json)
        assertEquals("Pull up", t.name)
        assertEquals(15, t.targetCount)
        assertEquals(19 * 60, t.deadlineMin)
        assertEquals(20, t.preOffsetMin)
        assertEquals(90, t.postOffsetMin)
        assertEquals(RepeatRule.DAILY, t.repeatMask)
    }

    @Test
    fun `键名忽略下划线与大小写差异`() {
        val json = """{"tasks":[{"task_name":"Plank","target_count":3,"deadline_min":1260}]}"""
        val t = single(json)
        assertEquals("Plank", t.name)
        assertEquals(3, t.targetCount)
        assertEquals(21 * 60, t.deadlineMin)
    }

    @Test
    fun `多种时间写法都能识别`() {
        // "20:00" / 数字分钟 / 12小时制 / 中文"点"
        assertEquals(1200, deadlineOf("\"deadline\":\"20:00\""))
        assertEquals(1200, deadlineOf("\"deadline\":1200"))
        assertEquals(1200, deadlineOf("\"deadline\":\"8:00 PM\""))
        assertEquals(1230, deadlineOf("\"deadline\":\"20点30\""))
        assertEquals(540, deadlineOf("\"deadline\":\"9:00\""))
        // 超过一天会归一化到当日
        assertEquals(60, deadlineOf("\"deadline\":1500"))
    }

    @Test
    fun `重复规则的多种写法`() {
        assertEquals(RepeatRule.DAILY, repeatOf("\"repeat\":\"daily\""))
        assertEquals(RepeatRule.DAILY, repeatOf("\"repeat\":\"每天\""))
        assertEquals(RepeatRule.WEEKDAYS, repeatOf("\"repeat\":\"weekdays\""))
        assertEquals(RepeatRule.WEEKDAYS, repeatOf("\"repeat\":\"工作日\""))
        assertEquals(RepeatRule.WEEKEND, repeatOf("\"repeat\":\"周末\""))
        // [1,3,5] -> 周一三五 -> 位 1,3,5
        assertEquals(0b0101010, repeatOf("\"repeat\":[1,3,5]"))
        // 中英文星期名等价
        assertEquals(0b0101010, repeatOf("\"repeat\":[\"mon\",\"wed\",\"fri\"]"))
        assertEquals(0b0101010, repeatOf("\"repeat\":[\"周一\",\"周三\",\"周五\"]"))
        // 直接给 7 位掩码
        assertEquals(RepeatRule.DAILY, repeatOf("\"repeat\":127"))
        // 周日 + 周六：0 体系与 ISO 体系都能落到同一结果
        assertEquals(65, repeatOf("\"repeat\":[0,6]"))
        assertEquals(65, repeatOf("\"repeat\":[6,7]"))
    }

    @Test
    fun `按星期任务的起止日期认嵌套写法`() {
        // v3.34.7：起点/结束嵌在 repeat 对象里（AI 常见写法）。修复前只认顶层，
        // 嵌套写法会静默丢掉整个期间约束——任务变成"mask 日起天天触发"，
        // 计划页/统计里会出现在建档日之前的某天（用户实测：上周三、上上周三）
        val t = single(
            """{"tasks":[{"name":"穿正装","deadline":"08:00","repeat":{"type":"weekday","days":["mon","tue","wed"],"startDate":"2026-10-19","endDate":"2026-10-21"}}]}"""
        )
        assertEquals("2026-10-19", t.anchorDate)
        assertEquals("2026-10-21", t.endDate)
        assertEquals((1 shl 1) or (1 shl 2) or (1 shl 3), t.repeatMask)
        assertTrue(RepeatRule.shouldTriggerTask(t, "2026-10-20", Calendar.TUESDAY))
        assertFalse(RepeatRule.shouldTriggerTask(t, "2026-10-14", Calendar.WEDNESDAY))

        // 顶层写法（契约 v6）仍然优先
        val top = single(
            """{"tasks":[{"name":"穿正装","deadline":"08:00","startDate":"2026-10-19","endDate":"2026-10-21","repeat":{"type":"weekday","days":["mon","tue","wed"]}}]}"""
        )
        assertEquals("2026-10-19", top.anchorDate)
        assertEquals("2026-10-21", top.endDate)
    }

    @Test
    fun `顶层支持裸数组与单个对象`() {
        assertEquals(2, TaskImporter.parse("""[{"name":"a"},{"name":"b"}]""").tasks.size)
        assertEquals(1, TaskImporter.parse("""{"name":"a"}""").tasks.size)
    }

    @Test
    fun `缺少名称的任务被跳过并给出提示`() {
        val r = TaskImporter.parse("""{"tasks":[{"deadline":"20:00"},{"name":"有效"}]}""")
        assertTrue(r.tasks.isEmpty() || r.tasks.size == 1)
        assertEquals(1, r.tasks.size)
        assertEquals("有效", r.tasks[0].name)
        assertTrue("应包含跳过提示", r.warnings.any { it.contains("缺少任务名称") })
    }

    @Test
    fun `非法内容与空内容返回错误`() {
        val bad = TaskImporter.parse("{这不是 json")
        assertFalse(bad.ok)
        assertTrue(bad.tasks.isEmpty())

        val empty = TaskImporter.parse("   ")
        assertFalse(empty.ok)
    }

    @Test
    fun `带单位的数字也能取到`() {
        val t = single("""{"tasks":[{"name":"俯卧撑","target":"60次"}]}""")
        assertEquals(60, t.targetCount)
    }

    @Test
    fun `未提供时间时默认 20 点并提示`() {
        val json = """{"tasks":[{"name":"拉伸"}]}"""
        val t = single(json)
        assertEquals(20 * 60, t.deadlineMin)
        // 缺省项走合理默认值，不报错
        assertEquals(30, t.preOffsetMin)
        assertEquals(120, t.postOffsetMin)
    }

    @Test
    fun `超过上限的任务被截断`() {
        val sb = StringBuilder("""{"tasks":[""")
        for (i in 1..60) sb.append("""{"name":"任务$i"},""")
        sb.deleteCharAt(sb.length - 1).append("]}")
        val r = TaskImporter.parse(sb.toString())
        assertEquals(TaskImporter.MAX_TASKS, r.tasks.size)
        assertTrue(r.warnings.any { it.contains("上限") })
    }

    @Test
    fun `每组次数可解析并受限`() {
        val t = single("""{"tasks":[{"name":"悬挂抬腿","targetCount":60,"repsPerSet":10}]}""")
        assertEquals(10, t.repsPerSet)
        // 每组大于目标时收敛到目标，避免永远做不满
        val big = single("""{"tasks":[{"name":"悬挂抬腿","targetCount":60,"repsPerSet":999}]}""")
        assertEquals(60, big.repsPerSet)
        // 中文别名也认
        val zh = single("""{"tasks":[{"name":"悬挂抬腿","targetCount":60,"每组次数":10}]}""")
        assertEquals(10, zh.repsPerSet)
        // 不填时 0（一次性勾选）
        val none = single("""{"tasks":[{"name":"拉伸"}]}""")
        assertEquals(0, none.repsPerSet)
    }

    @Test
    fun `节假日跳过可导入且缺省开启`() {
        val off = single("""{"tasks":[{"name":"锻炼","skipHolidays":false}]}""")
        assertFalse(off.skipHolidays)
        // 中文字段名 / 显式 true 都认
        val zh = single("""{"tasks":[{"name":"锻炼","跳过周末":false}]}""")
        assertFalse(zh.skipHolidays)
        val on = single("""{"tasks":[{"name":"锻炼","skipHolidays":true}]}""")
        assertTrue(on.skipHolidays)
        // 不填时默认开启（与新建任务一致：周末 + 法定假日不提醒）
        val dft = single("""{"tasks":[{"name":"锻炼"}]}""")
        assertTrue(dft.skipHolidays)
    }

    @Test
    fun `休息日截止时间可导入`() {
        val t = single("""{"tasks":[{"name":"早读","deadline":"07:30","restDayDeadline":"09:00"}]}""")
        assertEquals(9 * 60, t.restDayDeadlineMin)
        // 工作日用主时间、周末用休息日时间
        assertEquals(7 * 60 + 30, t.deadlineOn("2026-10-13"))   // 周二
        assertEquals(9 * 60, t.deadlineOn("2026-10-11"))        // 周日
        // 中文别名也认
        val zh = single("""{"tasks":[{"name":"早读","deadline":"07:30","休息日时间":"09:00"}]}""")
        assertEquals(9 * 60, zh.restDayDeadlineMin)
        // 不填 = 与主时间相同
        val none = single("""{"tasks":[{"name":"早读","deadline":"07:30"}]}""")
        assertEquals(-1, none.restDayDeadlineMin)
        assertEquals(7 * 60 + 30, none.deadlineOn("2026-10-11"))
    }

    /* ---------------- helper ---------------- */

    private fun single(json: String) = TaskImporter.parse(json).tasks.let {
        assertEquals(1, it.size)
        it[0]
    }

    private fun deadlineOf(field: String) =
        TaskImporter.parse("""{"tasks":[{${'"'}name${'"'}:"x",$field}]}""").tasks[0].deadlineMin

    private fun repeatOf(field: String) =
        TaskImporter.parse("""{"tasks":[{"name":"x",$field}]}""").tasks[0].repeatMask
}
