package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.model.ChatSessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatSessionDao {
    @Query("SELECT * FROM chat_sessions ORDER BY lastModifiedAt DESC LIMIT :limit")
    fun getLastSessions(limit: Int = 5): Flow<List<ChatSessionEntity>>

    @Query("SELECT * FROM chat_sessions ORDER BY lastModifiedAt DESC")
    fun getAllSessions(): Flow<List<ChatSessionEntity>>

    @Query("SELECT * FROM chat_sessions WHERE id = :sessionId LIMIT 1")
    suspend fun getSessionById(sessionId: Long): ChatSessionEntity?

    @Query("SELECT * FROM chat_sessions WHERE associatedProjectId = :projectId LIMIT 1")
    suspend fun getSessionForProject(projectId: Long): ChatSessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: ChatSessionEntity): Long

    @Update
    suspend fun updateSession(session: ChatSessionEntity)

    @Query("DELETE FROM chat_sessions WHERE id = :sessionId")
    suspend fun deleteSession(sessionId: Long)

    @Query("SELECT COUNT(*) FROM chat_sessions")
    suspend fun getSessionCount(): Int

    @Query("DELETE FROM chat_sessions")
    suspend fun clearAllSessions()
}
