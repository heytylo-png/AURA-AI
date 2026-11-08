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
    val allowPersonalContext: Boolean = false,
    /** Earlier turns of this conversation, oldest first; see [CloudHistory]. */
    val history: List<ChatTurn> = emptyList(),
    /** Let Claude propose changes (save memory, create project, add milestone) as approval cards. */
    val allowProposals: Boolean = false,
    /** Open to-dos the user chose to share: titles and due dates only (see [CloudTodos]). */
    val openTodos: List<CloudTodo> = emptyList()
)

/** What Claude may see of one open to-do. Notes, project and history never leave the phone. */
data class CloudTodo(val title: String, val dueDate: String?)

object CloudTodos {
    const val MAX = 30

    /** Open, non-deleted to-dos, soonest due first (undated last), at most [MAX], titles and dates only. */
    fun from(todos: List<com.example.data.model.TodoEntity>): List<CloudTodo> = todos
        .filter { !it.done && it.deletedAt == null }
        .sortedWith(com.example.data.model.TodoRules.openOrder)
        .take(MAX)
        .map { CloudTodo(it.title, it.dueEpochDay?.let { d -> com.example.data.model.TodoRules.formatDue(d) }) }
}

sealed interface AiProviderEvent {
    data class Content(val text: String) : AiProviderEvent
    /** [modelName] is the model that actually answered, shown to the user (e.g. after a fallback). */
    data class Completed(
        val fullText: String,
        val latencyMs: Long,
        val modelName: String? = null,
        /** A change Claude proposed with a tool in this reply; becomes an approval card after checks. */
        val toolCall: ToolCall? = null
    ) : AiProviderEvent
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


/**
 * Removes every personal-context field before a request crosses the cloud
 * provider boundary. Keep this as the default until a consent and sensitivity
 * filtering policy is implemented centrally.
 */
internal fun AiProviderRequest.withoutPersonalContext(): AiProviderRequest = copy(
    identity = identity.forCloud(),
    activeMemories = emptyList(),
    currentProject = null,
    userSettings = null,
    allowPersonalContext = false
)

/** Longest assistant name sent to the cloud. */
internal const val MAX_CLOUD_NAME_LENGTH = 40

/**
 * The parts of the assistant's identity that may go to the cloud: its name, personality preset and
 * answer length. Custom directives and core values are free text that can hold personal details,
 * so they are replaced with the app defaults until a consent setting covers them.
 */
internal fun AiIdentityConfig.forCloud(): AiIdentityConfig {
    val defaults = AiIdentityConfig()
    val cleanName = name.replace(Regex("\\s+"), " ").trim().take(MAX_CLOUD_NAME_LENGTH)
    return defaults.copy(
        name = cleanName.ifBlank { defaults.name },
        archetype = archetype,
        responseLength = responseLength,
        // The default directive talks about "active goals and project constraints", which the
        // cloud never receives; sending it made Claude claim context it does not have.
        customDirectives = ""
    )
}

/** Most memories sent to the cloud in one request. */
internal const val MAX_CLOUD_MEMORIES = 12

/**
 * What may leave the phone with a cloud request.
 *
 * Without the user's sharing switch: nothing personal (see [withoutPersonalContext]).
 * With it: the user's name and main interest, and only the active memories the user marked
 * "Share with Claude" (at most [MAX_CLOUD_MEMORIES]). Projects, custom directives and core values
 * never go, and memories not marked stay on the phone.
 */
internal fun AiProviderRequest.forCloud(sharePersonalContext: Boolean): AiProviderRequest {
    if (!sharePersonalContext) return withoutPersonalContext()
    return withoutPersonalContext().copy(
        activeMemories = activeMemories.filter { it.isActive && it.shareWithCloud }.take(MAX_CLOUD_MEMORIES),
        userSettings = userSettings?.takeIf { it.userName.isNotBlank() || it.primaryInterest.isNotBlank() },
        allowPersonalContext = true
    )
}
