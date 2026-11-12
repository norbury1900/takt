package com.norbury.taktu.data

import com.norbury.taktu.util.LunarCalendar
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Pure reminder planning: preserve the due date even when the alert crosses midnight. */
object ReminderSchedule {
    const val PRE = 0
    const val POST = 1

    /** One occurrence, including the date whose completion record must be checked. */
    data class Occurrence(val triggerAt: Long, val dateKey: String, val timeIndex: Int)

    /** Find the next eligible date without a fixed look-ahead limit for sparse tasks. */
    fun nextDueDate(task: Task, from: LocalDate): LocalDate? {
        val end = task.endDate.takeIf { it.isNotBlank() }?.let(LocalDate::parse)
        if (end != null && from > end) return null
        val candidate = when (task.repeatType) {
            RepeatRule.TYPE_ONCE -> task.anchorDate.takeIf { it.isNotBlank() }
                ?.let(LocalDate::parse)?.takeIf { it >= from }
            RepeatRule.TYPE_DATE -> {
                if (task.anchorDate.isBlank()) return null
                val anchor = LocalDate.parse(task.anchorDate)
                val start = maxOf(from, anchor)
                if (task.repeatMask != 0) {
                    // 多选日（每月/每年）：逐日找下一个命中日，800 天足以覆盖年度任务
                    (0L..800L).asSequence().map { start.plusDays(it) }
                        .takeWhile { end == null || it <= end }
                        .firstOrNull {
                            RepeatRule.isDateDue(
                                task.anchorDate, task.intervalMonths, task.endDate,
                                it.toString(), 0, RepeatRule.dayMaskOf(task.repeatMask),
                                task.monthsMask
                            )
                        }
                } else if (task.intervalDays > 0) {
                    val step = task.intervalDays.toLong()
                    val days = ChronoUnit.DAYS.between(anchor, start)
                    anchor.plusDays(((days + step - 1) / step) * step)
                } else {
                    val step = task.intervalMonths.coerceAtLeast(1).toLong()
                    val months = ChronoUnit.MONTHS.between(
                        anchor.withDayOfMonth(1), start.withDayOfMonth(1)
                    )
                    val cycles = months / step
                    anchor.plusMonths(cycles * step).let {
                        if (it >= start) it else anchor.plusMonths((cycles + 1) * step)
                    }
                }
            }
            RepeatRule.TYPE_LUNAR -> {
                val parts = task.anchorDate.split('-')
                val month = parts.getOrNull(0)?.toIntOrNull() ?: return null
                val day = parts.getOrNull(1)?.toIntOrNull() ?: return null
                val base = RepeatRule.lunarBaseYear(month, day, task.createdAt)
                val step = task.intervalMonths.coerceAtLeast(1)
                (maxOf(from.year - 1, base)..2100).asSequence()
                    .filter { (it - base) % step == 0 }
                    .mapNotNull { LunarCalendar.lunarToSolar(it, month, day) }
                    .map { it.minusDays(task.lunarLeadDays.coerceAtLeast(0).toLong()) }
                    .firstOrNull { it >= from }
            }
            else -> {
                if (task.repeatMask == 0) return null
                val start = maxOf(from, LocalDate.parse(RepeatRule.weekdayStartKey(task)))
                // Weekly masks always recur; one year also covers holiday exclusions.
                (0L..366L).asSequence().map { start.plusDays(it) }
                    .takeWhile { end == null || it <= end }
                    .firstOrNull {
                        RepeatRule.shouldTriggerTask(task, it.toString(), it.dayOfWeek.value % 7 + 1)
                    }
            }
        }
        return candidate?.takeIf { end == null || it <= end }
    }

    /** Calculate enabled reminders on a due date, applying quiet windows on the actual alert date. */
    fun onDate(
        task: Task, date: LocalDate, type: Int,
        quiet: List<QuietWindow> = emptyList(), zone: ZoneId = ZoneId.systemDefault()
    ): List<Occurrence> {
        if (!task.enabled || type !in PRE..POST) return emptyList()
        val key = date.toString()
        if (!RepeatRule.shouldTriggerTask(task, key, date.dayOfWeek.value % 7 + 1)) {
            return emptyList()
        }
        return task.timesOn(key).mapIndexedNotNull { index, minute ->
            val offset = if (type == PRE) task.preOffsetOf(minute) else task.postOffsetOf(minute)
            if (offset < 0) return@mapIndexedNotNull null
            val raw = minute.toLong() + if (type == PRE) -offset.toLong() else offset.toLong()
            var local = date.atStartOfDay().plusMinutes(raw)
            // Each matching window moves forward, including windows ending at the next midnight.
            quiet.sortedWith(compareBy({ it.dateKey }, { it.startMin })).forEach { window ->
                val start = LocalDate.parse(window.dateKey).atStartOfDay()
                val begin = start.plusMinutes(window.startMin.toLong())
                val end = start.plusMinutes(window.endMin.toLong())
                if (local >= begin && local < end) local = end
            }
            Occurrence(local.atZone(zone).toInstant().toEpochMilli(), key, index)
        }
    }

    /** Schedule only the earliest batch of each type; every delivery schedules its successor. */
    fun next(
        task: Task, now: Long, type: Int,
        quiet: List<QuietWindow> = emptyList(), zone: ZoneId = ZoneId.systemDefault()
    ): List<Occurrence> {
        if (!task.enabled || type !in PRE..POST) return emptyList()
        val offsets = TimeOffsets.parse(task.timeOffsets).values
        val maxPre = maxOf(task.preOffsetMin, offsets.maxOfOrNull { it.first } ?: -1, 0)
        val maxPost = maxOf(task.postOffsetMin, offsets.maxOfOrNull { it.second } ?: -1, 0)
        if (type == PRE && !task.hasPreReminder() || type == POST && !task.hasPostReminder()) {
            return emptyList()
        }
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        var from = today.minusDays(if (type == POST) maxPost / 1440L + 1 else 0)
        // A quiet window can defer an earlier due date into today.
        quiet.filter { it.dateKey <= today.toString() }.minOfOrNull { it.dateKey }?.let {
            from = minOf(from, LocalDate.parse(it).minusDays(maxPost / 1440L + 1))
        }
        var earliest = Long.MAX_VALUE
        val result = mutableListOf<Occurrence>()
        while (true) {
            val due = nextDueDate(task, from) ?: break
            val lowerBound = due.atStartOfDay().minusMinutes(
                if (type == PRE) maxPre.toLong() else 0
            ).atZone(zone).toInstant().toEpochMilli()
            if (lowerBound > earliest) break
            onDate(task, due, type, quiet, zone).filter { it.triggerAt > now + 1000 }.forEach {
                if (it.triggerAt < earliest) {
                    earliest = it.triggerAt
                    result.clear()
                }
                if (it.triggerAt == earliest) result.add(it)
            }
            from = due.plusDays(1)
        }
        return result
    }

    /** Snoozes bypass the original delivery marker, never disabled or completed occurrences. */
    fun shouldRing(
        task: Task, record: TaskRecord?, dateKey: String, timeIndex: Int,
        type: Int, alreadyFired: Boolean, snoozed: Boolean
    ): Boolean {
        if (!task.enabled || type !in PRE..POST) return false
        val date = LocalDate.parse(dateKey)
        if (!RepeatRule.shouldTriggerTask(task, dateKey, date.dayOfWeek.value % 7 + 1)) return false
        val time = task.timesOn(dateKey).getOrNull(timeIndex) ?: return false
        val offset = if (type == PRE) task.preOffsetOf(time) else task.postOffsetOf(time)
        return offset >= 0 && !task.allTimesConfirmed(record) &&
            !(task.timeSpec.isNotBlank() && task.timeConfirmed(record, timeIndex)) &&
            (!alreadyFired || snoozed)
    }
}
