package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.example.data.model.TodoEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TodoDao {
    /** Every to-do that isn't in Recently deleted (open and done). */
    @Query("SELECT * FROM todos WHERE deletedAt IS NULL")
    fun observeVisible(): Flow<List<TodoEntity>>

    @Query("SELECT * FROM todos WHERE deletedAt IS NOT NULL ORDER BY deletedAt DESC")
    fun observeDeleted(): Flow<List<TodoEntity>>

    @Query("SELECT COUNT(*) FROM todos WHERE deletedAt IS NULL AND done = 0")
    fun observeOpenCount(): Flow<Int>

    @Query("SELECT * FROM todos WHERE deletedAt IS NULL AND done = 0")
    suspend fun getOpen(): List<TodoEntity>

    @Query("SELECT * FROM todos WHERE id = :id")
    suspend fun getById(id: Long): TodoEntity?

    @Insert
    suspend fun insert(todo: TodoEntity): Long

    @Update
    suspend fun update(todo: TodoEntity)

    @Query("DELETE FROM todos WHERE id = :id AND deletedAt IS NOT NULL")
    suspend fun deleteForever(id: Long): Int

    @Query("DELETE FROM todos WHERE deletedAt IS NOT NULL AND deletedAt < :cutoff")
    suspend fun purgeDeletedBefore(cutoff: Long): Int

    /** A deleted project leaves its to-dos without a project rather than deleting them. */
    @Query("UPDATE todos SET projectId = NULL WHERE projectId = :projectId")
    suspend fun clearProject(projectId: Long)
}
