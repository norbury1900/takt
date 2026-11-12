package com.norbury.taktu.data

import android.content.Context
import com.norbury.taktu.util.MiLinkBridge

/**
 * 打卡落库的唯一入口（v3.34.114）。
 *
 * 以前首页 / 打卡页 / 响铃页 / 桌面组件各写一份「任务表缓存 + 当天记录 + MiLink」，
 * 规则一变就漏改一处（多时刻合并、择一提醒、跨天次数都因此出过问题）。
 * 现在写库只有这一个出口：
 *   1. 多时刻（含择一提醒）完成态统一按 [Task.allTimesConfirmed] 重算；
 *   2. tasks.doneCount 缓存与当天记录一起写；
 *   3. MiLink 同步用同一条记录的 completed，不再各算各的。
 * 其余字段（清单勾选、组数、时刻条目）由调用方构造，原样保留。
 */
object CheckIn {

    /** 记录键：账期任务记在本期到期日，其余记今天 */
    fun key(task: Task): String = task.recordKeyOn(AppDatabase.todayKey())

    fun load(dao: TaskDao, task: Task): TaskRecord =
        dao.getRecordSync(key(task), task.id)
            ?: TaskRecord(dateKey = key(task), taskId = task.id, completed = false)

    /** 纯函数：按任务类型重算完成态（便于单测） */
    fun resolve(task: Task, rec: TaskRecord): TaskRecord =
        if (task.isMultiTime) rec.copy(completed = task.allTimesConfirmed(rec)) else rec

    /**
     * 读—改—写串行化：首页、打卡页、响铃页、组件可能同时改同一条记录，
     * 以前各自「先读再写」，连点或跨入口同时操作会丢一次修改（v3.34.119）。
     * 所有写路径都走这里，锁只包住加载与提交。
     */
    private val lock = Any()

    fun update(
        context: Context,
        dao: TaskDao,
        task: Task,
        mutate: (TaskRecord) -> TaskRecord
    ): TaskRecord = synchronized(lock) {
        commit(context, dao, task, mutate(load(dao, task)))
    }

    /** 唯一写库出口：任务表缓存 + 当天记录 + MiLink */
    fun commit(context: Context, dao: TaskDao, task: Task, rec: TaskRecord): TaskRecord {
        val next = resolve(task, rec)
        dao.setDoneCountSync(task.id, next.doneCount)
        dao.upsertRecordSync(next)
        MiLinkBridge.syncCompletion(context, task, next.dateKey, next.completed)
        return next
    }
}
