package com.example.data.repository

import com.example.data.local.ChatMessageDao
import com.example.data.local.ChatSessionDao
import com.example.data.model.ChatMessageEntity
import com.example.data.model.ChatSessionEntity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * Repository interface defining operations for managing and persisting
 * AI conversation history and chat sessions.
 */
interface ChatRepository {
    /**
     * Observes all global (unscoped) chat messages in chronological order.
     */
    fun getGlobalMessages(): Flow<List<ChatMessageEntity>>

    /**
     * Observes chat messages scoped to a specific project.
     */
    fun getMessagesForProject(projectId: Long): Flow<List<ChatMessageEntity>>

    /**
     * Observes chat messages scoped to a specific conversation session.
     */
    fun getMessagesForSession(sessionId: Long): Flow<List<ChatMessageEntity>>

    /**
     * Retrieves recent messages snapshot for context building.
     */
    suspend fun getRecentMessages(limit: Int): List<ChatMessageEntity>

    /**
     * Persists a new message (user or AI) to local database storage.
     * @return The auto-generated message ID.
     */
    suspend fun insertMessage(message: ChatMessageEntity): Long

    /**
     * Deletes a specific message by its unique ID.
     */
    suspend fun deleteMessage(messageId: Long)

    /**
     * Clears all global conversation history.
     */
    suspend fun clearGlobalChat()

    /**
     * Clears all messages associated with a given project.
     */
    suspend fun clearProjectChat(projectId: Long)

    /**
     * Clears all messages in a specific chat session.
     */
    suspend fun clearSessionChat(sessionId: Long)

    // Chat Sessions
    fun getLastSessions(limit: Int = 5): Flow<List<ChatSessionEntity>>
    fun getAllSessions(): Flow<List<ChatSessionEntity>>
    suspend fun getSessionById(sessionId: Long): ChatSessionEntity?
    suspend fun getSessionForProject(projectId: Long): ChatSessionEntity?
    suspend fun createNewSession(title: String = "New Conversation", projectId: Long? = null): Long
    suspend fun updateSession(session: ChatSessionEntity)
    suspend fun deleteSession(sessionId: Long)
}

/**
 * Default implementation of [ChatRepository] backed by Room SQLite via [ChatMessageDao] and [ChatSessionDao].
 */
class DefaultChatRepository @JvmOverloads constructor(
    private val chatMessageDao: ChatMessageDao,
    private val chatSessionDao: ChatSessionDao? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ChatRepository {

    override fun getGlobalMessages(): Flow<List<ChatMessageEntity>> {
        return chatMessageDao.getGlobalMessages().flowOn(ioDispatcher)
    }

    override fun getMessagesForProject(projectId: Long): Flow<List<ChatMessageEntity>> {
        return chatMessageDao.getMessagesForProject(projectId).flowOn(ioDispatcher)
    }

    override fun getMessagesForSession(sessionId: Long): Flow<List<ChatMessageEntity>> {
        return chatMessageDao.getMessagesForSession(sessionId).flowOn(ioDispatcher)
    }

    override suspend fun getRecentMessages(limit: Int): List<ChatMessageEntity> = withContext(ioDispatcher) {
        chatMessageDao.getRecentMessages(limit)
    }

    override suspend fun insertMessage(message: ChatMessageEntity): Long = withContext(ioDispatcher) {
        val id = chatMessageDao.insertMessage(message)
        // Automatically touch session lastMessageSnippet and timestamp
        chatSessionDao?.let { dao ->
            val session = dao.getSessionById(message.sessionId)
            if (session != null) {
                val updatedTitle = if (session.title == "New Conversation" && message.text.isNotBlank()) {
                    message.text.take(30).trim()
                } else {
                    session.title
                }
                dao.updateSession(
                    session.copy(
                        title = updatedTitle,
                        lastMessageSnippet = message.text.take(65).trim(),
                        lastModifiedAt = message.timestamp
                    )
                )
            }
        }
        id
    }

    override suspend fun deleteMessage(messageId: Long) = withContext(ioDispatcher) {
        chatMessageDao.deleteMessage(messageId)
    }

    override suspend fun clearGlobalChat() = withContext(ioDispatcher) {
        chatMessageDao.clearGlobalChat()
    }

    override suspend fun clearProjectChat(projectId: Long) = withContext(ioDispatcher) {
        chatMessageDao.clearProjectChat(projectId)
    }

    override suspend fun clearSessionChat(sessionId: Long) = withContext(ioDispatcher) {
        chatMessageDao.clearSessionChat(sessionId)
        chatSessionDao?.let { dao ->
            val session = dao.getSessionById(sessionId)
            if (session != null) {
                dao.updateSession(session.copy(lastMessageSnippet = "", lastModifiedAt = System.currentTimeMillis()))
            }
        }
        Unit
    }

    override fun getLastSessions(limit: Int): Flow<List<ChatSessionEntity>> {
        return chatSessionDao?.getLastSessions(limit)?.flowOn(ioDispatcher) ?: emptyFlow()
    }

    override fun getAllSessions(): Flow<List<ChatSessionEntity>> {
        return chatSessionDao?.getAllSessions()?.flowOn(ioDispatcher) ?: emptyFlow()
    }

    override suspend fun getSessionById(sessionId: Long): ChatSessionEntity? = withContext(ioDispatcher) {
        chatSessionDao?.getSessionById(sessionId)
    }

    override suspend fun getSessionForProject(projectId: Long): ChatSessionEntity? = withContext(ioDispatcher) {
        chatSessionDao?.getSessionForProject(projectId)
    }

    override suspend fun createNewSession(title: String, projectId: Long?): Long = withContext(ioDispatcher) {
        chatSessionDao?.insertSession(
            ChatSessionEntity(
                title = title,
                associatedProjectId = projectId,
                lastModifiedAt = System.currentTimeMillis()
            )
        ) ?: 1L
    }

    override suspend fun updateSession(session: ChatSessionEntity) = withContext(ioDispatcher) {
        chatSessionDao?.updateSession(session)
        Unit
    }

    override suspend fun deleteSession(sessionId: Long) = withContext(ioDispatcher) {
        chatMessageDao.clearSessionChat(sessionId)
        chatSessionDao?.deleteSession(sessionId)
        Unit
    }
}
