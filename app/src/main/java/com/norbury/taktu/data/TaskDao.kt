package com.norbury.taktu.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {

    @Query("SELECT * FROM tasks ORDER BY sortOrder ASC, id ASC")
    fun observeAll(): Flow<List<Task>>

    @Query("SELECT * FROM tasks ORDER BY sortOrder ASC, id ASC")
    suspend fun getAll(): List<Task>

    /** 同步版本：重排闹钟时取消全部旧闹钟用（**含已停用任务**，防幽灵闹钟） */
    @Query("SELECT * FROM tasks ORDER BY sortOrder ASC, id ASC")
    fun getAllSync(): List<Task>

    /** 同步版本：供 BroadcastReceiver 在自己的工作线程中调用 */
    @Query("SELECT * FROM tasks WHERE id = :id")
    fun getByIdSync(id: Long): Task?

    @Query("SELECT * FROM tasks WHERE enabled = 1 ORDER BY sortOrder ASC, id ASC")
    fun getEnabledSync(): List<Task>

    @Query("SELECT * FROM tasks WHERE enabled = 1 ORDER BY sortOrder ASC, id ASC")
    suspend fun getEnabled(): List<Task>

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun getById(id: Long): Task?

    @Insert
    suspend fun insert(task: Task): Long

    @Update
    suspend fun update(task: Task)

    @Delete
    suspend fun delete(task: Task)

    /** 批量删任务（过期数据自动清理，v3.34.104） */
    @Query("DELETE FROM tasks WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    /** 批量删这些任务的完成记录 */
    @Query("DELETE FROM records WHERE taskId IN (:ids)")
    suspend fun deleteRecordsByTaskIds(ids: List<Long>)

    /** 清掉没有对应任务的孤儿记录（任务被删后残留的统计行） */
    @Query("DELETE FROM records WHERE taskId NOT IN (SELECT id FROM tasks)")
    suspend fun deleteOrphanRecords()

    @Query("UPDATE tasks SET doneCount = :done WHERE id = :id")
    suspend fun setDoneCount(id: Long, done: Int)

    /** 同步版本：供 BroadcastReceiver 在自己的工作线程中调用 */
    @Query("UPDATE tasks SET doneCount = :done WHERE id = :id")
    fun setDoneCountSync(id: Long, done: Int)

    /** 跨天清零组数进度（v3.34.112）：doneCount 只是当天进度的缓存，口径以当天记录为准 */
    @Query("UPDATE tasks SET doneCount = 0")
    suspend fun resetDoneCounts()

    /** 标签改名 / 删除时批量搬移任务（删除 = newTag 空串 → 回到未分组） */
    @Query("UPDATE tasks SET tag = :newTag WHERE tag = :oldTag")
    suspend fun migrateTag(oldTag: String, newTag: String)

    // ------- 记录 -------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRecord(record: TaskRecord)

    /** 同步版本：供 BroadcastReceiver 在自己的工作线程中调用 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertRecordSync(record: TaskRecord)

    @Query("SELECT * FROM records WHERE dateKey = :dateKey AND taskId = :taskId")
    suspend fun getRecord(dateKey: String, taskId: Long): TaskRecord?

    /** 同步版本：供 BroadcastReceiver 在自己的工作线程中调用 */
    @Query("SELECT * FROM records WHERE dateKey = :dateKey AND taskId = :taskId")
    fun getRecordSync(dateKey: String, taskId: Long): TaskRecord?

    @Query("SELECT * FROM records WHERE dateKey = :dateKey")
    suspend fun getRecordsOfDate(dateKey: String): List<TaskRecord>

    @Query("SELECT * FROM records WHERE dateKey >= :fromKey ORDER BY dateKey DESC")
    suspend fun getRecordsSince(fromKey: String): List<TaskRecord>

    @Query("SELECT * FROM records WHERE taskId = :taskId ORDER BY dateKey DESC")
    suspend fun getRecordsOfTask(taskId: Long): List<TaskRecord>
}
