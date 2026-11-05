package com.example.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.model.ActionStatus
import com.example.data.model.TaskActionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskActionDao {
    @Query("SELECT * FROM task_actions ORDER BY createdAt DESC")
    fun getAllActions(): Flow<List<TaskActionEntity>>

    @Query("SELECT * FROM task_actions WHERE status = :status ORDER BY createdAt DESC")
    fun getActionsByStatus(status: ActionStatus): Flow<List<TaskActionEntity>>

    @Query("SELECT * FROM task_actions WHERE id = :id LIMIT 1")
    suspend fun getActionById(id: Long): TaskActionEntity?

    @Query("SELECT COUNT(*) FROM task_actions WHERE status = 'PROPOSED'")
    fun getPendingApprovalCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAction(action: TaskActionEntity): Long

    @Update
    suspend fun updateAction(action: TaskActionEntity)

    @Delete
    suspend fun deleteAction(action: TaskActionEntity)

    @Query("UPDATE task_actions SET status = :status, executedAt = :executedAt WHERE id = :id")
    suspend fun updateActionStatus(id: Long, status: ActionStatus, executedAt: Long? = null)

    /** Claims a PROPOSED action for execution. Returns 0 if it was not PROPOSED (so it must not run). */
    @Query("UPDATE task_actions SET status = 'EXECUTING' WHERE id = :id AND status = 'PROPOSED'")
    suspend fun claimForExecution(id: Long): Int

    /** Dismisses an action only while it is still PROPOSED. Returns rows changed. */
    @Query("UPDATE task_actions SET status = 'DISMISSED' WHERE id = :id AND status = 'PROPOSED'")
    suspend fun dismissIfProposed(id: Long): Int

    /** Actions still EXECUTING at startup were interrupted; their writes were rolled back. */
    @Query("UPDATE task_actions SET status = 'FAILED', executedAt = NULL WHERE status = 'EXECUTING'")
    suspend fun failInterruptedExecutions(): Int
}
