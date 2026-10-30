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
}
