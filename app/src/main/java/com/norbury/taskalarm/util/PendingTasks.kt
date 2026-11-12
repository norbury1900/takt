package com.norbury.taskalarm.util

import android.content.Context
import com.norbury.taskalarm.data.AppDatabase
import com.norbury.taskalarm.data.RepeatRule
import com.norbury.taskalarm.data.Task
import java.util.Calendar

/**
 * 「这个时间点还没做完」的任务清单 —— 响铃页与响铃通知共用同一套判定，避免两处逻辑走偏。
 *
 * 判定条件（三条全部满足）：
 *   1. 任务启用中
 *   2. 今天该提醒（含法定节假日 / 调休判定）
 *   3. 今天还没被标记完成
 *
 * 第 3 条走 [Task.allTimesConfirmed] 而不是 `record.completed`：多时刻任务
 * 只确认了一个时刻时，整条仍然是「今天还有没做完的时刻」，不该从清单里消失
 * （v3.34.21 用户反馈：中午那次响完勾掉，17:25 那次就没影了）。
 *
 * 这里**不要求"已到它自己的提醒点"**：既然已经响了，用户要的就是
 * 「今天还有哪些没做」的全貌，而不是只看到恰好同一时刻到点的那几条
 * （否则会出现"只提醒了运动分组、未分组的没出现"这种困惑）。
 *
 * 注意：会同步访问数据库，**必须在后台线程调用**。
 */
object PendingTasks {

    fun now(context: Context): List<Task> {
        val dao = AppDatabase.get(context).taskDao()
        val today = AppDatabase.todayKey()
        val cal = Calendar.getInstance()
        val dow = cal.get(Calendar.DAY_OF_WEEK)
        return dao.getEnabledSync()
            .filter { RepeatRule.shouldTriggerTask(it, today, dow) }
            .filter { !it.allTimesConfirmed(dao.getRecordSync(today, it.id)) }
    }
}
