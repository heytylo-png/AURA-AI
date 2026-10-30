package com.example.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.model.ChatMessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatMessageDao {
    @Query("SELECT * FROM chat_messages WHERE associatedProjectId IS NULL ORDER BY timestamp ASC")
    fun getGlobalMessages(): Flow<List<ChatMessageEntity>>

    @Query("SELECT * FROM chat_messages WHERE associatedProjectId = :projectId ORDER BY timestamp ASC")
    fun getMessagesForProject(projectId: Long): Flow<List<ChatMessageEntity>>

    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun getMessagesForSession(sessionId: Long): Flow<List<ChatMessageEntity>>

    @Query("SELECT * FROM chat_messages ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentMessages(limit: Int): List<ChatMessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: ChatMessageEntity): Long

    @Query("DELETE FROM chat_messages WHERE associatedProjectId IS NULL")
    suspend fun clearGlobalChat()

    @Query("DELETE FROM chat_messages WHERE id = :messageId")
    suspend fun deleteMessage(messageId: Long)

    @Query("DELETE FROM chat_messages WHERE associatedProjectId = :projectId")
    suspend fun clearProjectChat(projectId: Long)

    @Query("DELETE FROM chat_messages WHERE sessionId = :sessionId")
    suspend fun clearSessionChat(sessionId: Long)
}
