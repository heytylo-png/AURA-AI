package com.example.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.AppDatabase
import com.example.data.model.AiModelUsed
import com.example.data.model.ChatMessageEntity
import com.example.data.model.Sender
import com.example.data.repository.ChatRepository
import com.example.data.repository.DefaultChatRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ConversationUiState(
    val messages: List<ChatMessageEntity> = emptyList(),
    val activeProjectId: Long? = null,
    val isLoading: Boolean = false,
    val totalMessageCount: Int = 0
)

/**
 * Dedicated ViewModel to manage and persist conversation message history for the AI conversation UI.
 * Interacts exclusively through the [ChatRepository] layer.
 */
class ConversationViewModel @JvmOverloads constructor(
    application: Application,
    private val chatRepository: ChatRepository = DefaultChatRepository(
        AppDatabase.getInstance(application).chatMessageDao()
    )
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(ConversationUiState())
    val uiState: StateFlow<ConversationUiState> = _uiState.asStateFlow()

    private var messageCollectionJob: Job? = null

    init {
        observeMessages(projectId = null)
    }

    /**
     * Updates the active conversation context between global and project-scoped chats.
     */
    fun setProjectScope(projectId: Long?) {
        _uiState.update { it.copy(activeProjectId = projectId) }
        observeMessages(projectId)
    }

    private fun observeMessages(projectId: Long?) {
        messageCollectionJob?.cancel()
        messageCollectionJob = viewModelScope.launch {
            val flow = if (projectId == null) {
                chatRepository.getGlobalMessages()
            } else {
                chatRepository.getMessagesForProject(projectId)
            }

            flow.collect { messageList ->
                _uiState.update { currentState ->
                    currentState.copy(
                        messages = messageList,
                        totalMessageCount = messageList.size,
                        isLoading = false
                    )
                }
            }
        }
    }

    /**
     * Persists a message to the database synchronously via suspend function.
     */
    suspend fun persistMessageDirect(
        sender: Sender,
        text: String,
        modelUsed: AiModelUsed? = null,
        reasoningExplanation: String? = null,
        proposedActionId: Long? = null
    ): Long {
        val entity = ChatMessageEntity(
            sender = sender,
            text = text,
            modelUsed = modelUsed,
            reasoningExplanation = reasoningExplanation,
            associatedProjectId = _uiState.value.activeProjectId,
            proposedActionId = proposedActionId
        )
        return chatRepository.insertMessage(entity)
    }

    /**
     * Persists a message to the database via repository asynchronously.
     */
    fun persistMessage(
        sender: Sender,
        text: String,
        modelUsed: AiModelUsed? = null,
        reasoningExplanation: String? = null,
        proposedActionId: Long? = null
    ): Job = viewModelScope.launch {
        persistMessageDirect(sender, text, modelUsed, reasoningExplanation, proposedActionId)
    }

    /**
     * Deletes a specific message by ID synchronously.
     */
    suspend fun deleteMessageDirect(messageId: Long) {
        chatRepository.deleteMessage(messageId)
    }

    /**
     * Deletes a specific message by ID.
     */
    fun deleteMessage(messageId: Long): Job = viewModelScope.launch {
        deleteMessageDirect(messageId)
    }

    /**
     * Clears all messages in the active conversation scope synchronously.
     */
    suspend fun clearConversationDirect() {
        val projId = _uiState.value.activeProjectId
        if (projId == null) {
            chatRepository.clearGlobalChat()
        } else {
            chatRepository.clearProjectChat(projId)
        }
    }

    /**
     * Clears all messages in the active conversation scope.
     */
    fun clearConversation(): Job = viewModelScope.launch {
        clearConversationDirect()
    }

    override fun onCleared() {
        super.onCleared()
        messageCollectionJob?.cancel()
    }
}
