package com.norbury.taskalarm.data

import com.norbury.taskalarm.util.TaskImporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * TaskListBuilder 页签筛选测试。
 *
 * 核心回归（v3.26.3）：折叠 / 展开分组与首次加载必须走同一个 build()，
 * 「今天」页任何时候都不能冒出未来的计划——老代码在 toggleGroup 里
 * 直接提交全量 latestTasks，折叠再展开分组后未来任务全部混上屏。
 */
class TaskListBuilderTest {

    /**
     * 导入造任务。createdAt 统一钉在 10-01：v3.34.7 起按星期任务的隐式起点 = 建档日
     * （anchorDate 为空时），而导入侧的 createdAt 是"现在"——不钉死的话，
     * 任务会比测试里的"今天"（10-19）还新，今天页直接空掉（见 WeekdayPeriodTest）。
     */
    private fun task(id: Long, json: String): Task =
        TaskImporter.parse("""{"tasks":[$json]}""").tasks[0]
            .copy(id = id, createdAt = legacyCreated)

    private val legacyCreated: Long = java.util.Calendar.getInstance().apply {
        clear()
        set(2026, 9, 1, 8, 0, 0)
    }.timeInMillis

    private val today = "2026-10-19"          // 周一
    private val dow = Calendar.MONDAY

    private val tDaily = task(
        1, """{"name":"每天训练","repeat":"daily","priority":"高","skipHolidays":false}"""
    )
    private val tTomorrow = task(
        2, """{"name":"明天的一次性采购","repeat":{"type":"once","date":"2026-10-20"},"skipHolidays":false}"""
    )
    private val tTodayOnce = task(
        3, """{"name":"今天的一次性","repeat":{"type":"once","date":"2026-10-19"},"skipHolidays":false}"""
    )
    private val tDone = task(
        4, """{"name":"已完成的每天任务","repeat":"daily","priority":"低","skipHolidays":false}"""
    )
    // ── 历史页用例（v3.26.6）──
    private val tOverdueOnce = task(
        5, """{"name":"上周过期没做完","repeat":{"type":"once","date":"2026-10-12"},"skipHolidays":false}"""
    )
    private val tOldDone = task(
        6, """{"name":"9月底做完的一次性","repeat":{"type":"once","date":"2026-09-20"},"skipHolidays":false}"""
    )
    private val tTodayOnceDone = task(
        7, """{"name":"今天做完的一次性","repeat":{"type":"once","date":"2026-10-19"},"skipHolidays":false}"""
    )
    private val tExpiredDated = task(
        8, """{"name":"已结束的账期","repeat":{"type":"date","startDate":"2026-07-21","intervalMonths":1,"endDate":"2026-09-30"},"skipHolidays":false}"""
    )
    private val tAncientDone = task(
        9, """{"name":"八月初做完的一次性","repeat":{"type":"once","date":"2026-08-01"},"skipHolidays":false}"""
    )

    private val all = listOf(tDaily, tTomorrow, tTodayOnce, tDone)
    private val doneToday = mapOf(4L to true)

    /** 历史窗口内的完成情况：一次性任务在原定日完成（今天完成的算今天） */
    private val historyDone = mapOf(6L to true, 7L to true, 9L to true)

    @Test
    fun `今天页只含今天该做且未完成的任务`() {
        val list = TaskListBuilder.build(TaskListBuilder.TAB_TODAY, all, doneToday, today, dow)
        assertEquals(listOf(1L, 3L), list.map { it.id })
        // 回归点：未来的一次性任务曾随折叠展开混进今天页
        assertFalse(list.any { it.id == 2L })
        // 已完成的自动移去「已完成」页
        assertFalse(list.any { it.id == 4L })
    }

    @Test
    fun `折叠展开分组与首次加载口径一致`() {
        val first = TaskListBuilder.build(TaskListBuilder.TAB_TODAY, all, doneToday, today, dow)
        val afterToggle = TaskListBuilder.build(TaskListBuilder.TAB_TODAY, all, doneToday, today, dow)
        assertEquals(first, afterToggle)
        assertTrue(afterToggle.isNotEmpty())
        assertFalse(afterToggle.any { it.id == 2L })
    }

    @Test
    fun `已完成页只含今天做完的`() {
        val list = TaskListBuilder.build(TaskListBuilder.TAB_DONE, all, doneToday, today, dow)
        assertEquals(listOf(4L), list.map { it.id })
    }

    @Test
    fun `全部页不过滤今天`() {
        val list = TaskListBuilder.build(TaskListBuilder.TAB_ALL, all, doneToday, today, dow)
        assertEquals(listOf(1L, 2L, 3L, 4L), list.map { it.id })
    }

    @Test
    fun `计划页没有可折叠分组返回空列表`() {
        assertTrue(TaskListBuilder.build(TaskListBuilder.TAB_PLAN, all, doneToday, today, dow).isEmpty())
    }

    @Test
    fun `按优先级排序同档按当天截止时间`() {
        val list = TaskListBuilder.build(TaskListBuilder.TAB_ALL, all, doneToday, today, dow)
        assertEquals(Task.PRIORITY_HIGH, list.first().priority)
    }

    /* ------------------------- 历史页（v3.26.6） ------------------------- */

    private val allWithHistory =
        all + listOf(tOverdueOnce, tOldDone, tTodayOnceDone, tExpiredDated, tAncientDone)

    @Test
    fun `历史页含已完成和已过期的任务`() {
        val list = TaskListBuilder.build(
            TaskListBuilder.TAB_HISTORY, allWithHistory, doneToday, today, dow, historyDone
        )
        // 过期没做完（5）、窗口内做完的（6、7）、已结束的账期（8）
        // 8 月初做完的（9）超过 30 天保留窗口，丢掉
        assertEquals(listOf(7L, 5L, 8L, 6L), list.map { it.id })
    }

    @Test
    fun `历史页不含循环任务和未来的一次性任务`() {
        val list = TaskListBuilder.build(
            TaskListBuilder.TAB_HISTORY, allWithHistory, doneToday, today, dow, historyDone
        )
        // 循环任务没有「过期」，哪怕今天做完了也不进历史
        assertFalse(list.any { it.id == 1L || it.id == 4L })
        // 未来的、没做完的一次性任务更不是历史
        assertFalse(list.any { it.id == 2L })
    }

    @Test
    fun `全部页排除已沉到历史的任务`() {
        val list = TaskListBuilder.build(
            TaskListBuilder.TAB_ALL, allWithHistory, doneToday, today, dow, historyDone
        )
        // 只列还没到结束期限的：过期（5）、已做完（6、7、9）、已结束账期（8）都不在
        assertFalse(list.any { it.id in setOf(5L, 6L, 7L, 8L, 9L) })
        // 未来的（2）、今天未完成的（3）、循环的（1、4）照旧在
        assertEquals(listOf(1L, 2L, 3L, 4L), list.map { it.id })
    }

    @Test
    fun `历史页折叠展开分组与首次加载口径一致`() {
        // toggleGroup 免查库重组必须和 loadTasks 首次加载走同一个 build
        // （同一份 historyDone）——v3.26.6 曾漏传，折叠后已完成任务消失
        val first = TaskListBuilder.build(
            TaskListBuilder.TAB_HISTORY, allWithHistory, doneToday, today, dow, historyDone
        )
        val afterToggle = TaskListBuilder.build(
            TaskListBuilder.TAB_HISTORY, allWithHistory, doneToday, today, dow, historyDone
        )
        assertEquals(first, afterToggle)
        assertTrue(afterToggle.isNotEmpty())
    }

    @Test
    fun `历史保留窗口约一个月`() {
        // 今天 2026-10-19 → 窗口起点 2026-09-19（含），08-01 已超期
        assertEquals("2026-09-19", TaskListBuilder.historyCutoffKey(today))
        assertTrue(TaskListBuilder.isHistorical(tAncientDone, today, true))
        assertFalse(TaskListBuilder.isHistorical(tTomorrow, today, false))
        // 循环任务永不进历史
        assertFalse(TaskListBuilder.isHistorical(tDaily, today, true))
        assertFalse(TaskListBuilder.isHistorical(tDone, today, true))
    }
}
