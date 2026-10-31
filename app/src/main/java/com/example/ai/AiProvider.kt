package com.example.ai

import com.example.data.model.AiIdentityConfig
import com.example.data.model.MemoryEntity
import com.example.data.model.ProjectEntity
import com.example.data.model.UserSettingsEntity
import kotlinx.coroutines.flow.Flow

/**
 * Provider-neutral request. Personal context is excluded unless the caller
 * explicitly grants consent for this request.
 */
data class AiProviderRequest(
    val prompt: String,
    val identity: AiIdentityConfig,
    val activeMemories: List<MemoryEntity> = emptyList(),
    val currentProject: ProjectEntity? = null,
    val userSettings: UserSettingsEntity? = null,
    val allowPersonalContext: Boolean = false
)

sealed interface AiProviderEvent {
    data class Content(val text: String) : AiProviderEvent
    data class Completed(val fullText: String, val latencyMs: Long) : AiProviderEvent
    data class Error(val message: String) : AiProviderEvent
}

/** Describes a model provider without exposing provider-specific SDK types. */
data class AiProviderCapabilities(
    val streaming: Boolean,
    val supportsPersonalContext: Boolean,
    val supportsOffline: Boolean
)

/**
 * Common contract implemented by Gemini, OpenAI, Claude, and local adapters.
 * Implementations must not persist chat, memory, or project data themselves.
 */
interface AiProvider {
    val providerId: String
    val capabilities: AiProviderCapabilities

    fun generateStream(request: AiProviderRequest): Flow<AiProviderEvent>
}
