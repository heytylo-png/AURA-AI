package com.example.ai

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.example.data.model.AiIdentityConfig
import com.example.data.model.AiModelUsed
import com.example.data.model.MemoryEntity
import com.example.data.model.ProjectEntity
import com.example.data.model.TaskActionEntity
import com.example.data.model.UserSettingsEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow

enum class HybridRoutingMode(val label: String, val description: String) {
    AUTO_HYBRID("Auto Hybrid Router", "Claude answers when online and cloud chat is on; typed commands (remember…, add task:, create project…) and offline messages stay on this phone"),
    FORCE_LOCAL("Local Model Only", "100% on-device offline edge reasoning"),
    FORCE_CLOUD("Cloud Claude", "Anthropic's Claude via your private Aura relay")
}

sealed interface HybridAiState {
    data object Starting : HybridAiState
    data class Streaming(val text: String, val modelUsed: AiModelUsed) : HybridAiState
    data class Completed(
        val fullText: String,
        val modelUsed: AiModelUsed,
        val latencyMs: Long,
        val reasoningRationale: String,
        val proposedAction: TaskActionEntity? = null
    ) : HybridAiState
    data class Error(val message: String) : HybridAiState
}

class HybridAiEngine(
    private val context: Context,
    private val localEngine: LocalModelEngine = LocalModelEngine(),
    private val cloudEngine: AiProvider = ClaudeRelayProvider.create(),
    private val onlineStatusOverride: (() -> Boolean)? = null
) {

    private fun isOnline(): Boolean {
        onlineStatusOverride?.let { return it() }
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * Inspects query characteristics to determine if local execution is ideal.
     */
    /**
     * Auto mode, online, cloud chat on: Claude answers everything except a few typed commands that
     * the phone handles itself (and that never need to leave it). A command must START the message,
     * so "I prefer Kotlin, explain coroutines" or "who are you?" go to Claude, not to canned replies.
     */
    fun shouldRouteToLocal(prompt: String): Boolean {
        val lower = prompt.trim().lowercase()
        return LOCAL_COMMANDS.any { lower.startsWith(it) } || lower == "show memories"
    }

    fun processHybridRequest(
        prompt: String,
        mode: HybridRoutingMode,
        identity: AiIdentityConfig,
        activeMemories: List<MemoryEntity>,
        projects: List<ProjectEntity>,
        currentProject: ProjectEntity? = null,
        userSettings: UserSettingsEntity? = null,
        allowCloudRequest: Boolean = false,
        /** Earlier cloud exchanges of this conversation (see [CloudHistory]); sent only to the cloud. */
        history: List<ChatTurn> = emptyList(),
        /** The user's "share with Claude" switch: profile + memories they marked. Off = nothing personal. */
        sharePersonalContext: Boolean = false,
        /** The user's "Let Claude propose changes" switch: Claude may suggest one change per reply as a card. */
        allowProposals: Boolean = false,
        /** Open to-dos to share, already chosen by the user's "Share open to-dos" switch (empty = off). */
        sharedTodos: List<CloudTodo> = emptyList(),
        /** Total open to-dos, when [sharedTodos] may be capped. */
        sharedTodosTotal: Int = sharedTodos.size,
        /** Set only when the user shared the project being chatted in. */
        sharedProject: CloudProject? = null
    ): Flow<HybridAiState> = flow {
        emit(HybridAiState.Starting)
        val online = isOnline()

        val routeToLocal = when (mode) {
            HybridRoutingMode.FORCE_LOCAL -> true
            HybridRoutingMode.FORCE_CLOUD -> !online // Force cloud unless device is strictly offline
            HybridRoutingMode.AUTO_HYBRID -> !online || shouldRouteToLocal(prompt)
        }

        // Cloud use is explicitly opt-in.
        if (!routeToLocal && !allowCloudRequest) {
            if (mode == HybridRoutingMode.FORCE_CLOUD) {
                emit(notice(
                    "Nothing was sent: cloud chat is off. Turn on \"Cloud chat\" in Settings \u2192 Cloud AI, " +
                        "or switch to Auto or Local mode.",
                    "Cloud-only mode with cloud chat turned off."
                ))
            } else {
                val localResult = localEngine.processQuery(prompt, identity, activeMemories, projects, currentProject, userSettings)
                emit(
                    HybridAiState.Completed(
                        fullText = localResult.responseText,
                        modelUsed = AiModelUsed.LOCAL,
                        latencyMs = localResult.latencyMs,
                        reasoningRationale = "Cloud chat is off, so this was answered on this phone and nothing was sent.",
                        proposedAction = localResult.proposedAction
                    )
                )
            }
            return@flow
        }

        if (routeToLocal) {
            if (mode == HybridRoutingMode.FORCE_CLOUD) {
                emit(notice(
                    "You're offline, and Cloud-only mode can't answer without a connection. Nothing was sent.",
                    "Cloud-only mode while offline."
                ))
                return@flow
            }
            val localResult = localEngine.processQuery(prompt, identity, activeMemories, projects, currentProject, userSettings)
            val rationale = when {
                mode == HybridRoutingMode.FORCE_LOCAL -> "Local-only mode: answered on this phone; nothing was sent."
                !online -> "Offline: answered on this phone; nothing was sent."
                else -> "Auto mode: a simple command, answered on this phone; nothing was sent."
            }
            emit(
                HybridAiState.Completed(
                    fullText = localResult.responseText,
                    modelUsed = AiModelUsed.LOCAL,
                    latencyMs = localResult.latencyMs,
                    reasoningRationale = rationale,
                    proposedAction = localResult.proposedAction
                )
            )
            return@flow
        }

        val providerRequest = AiProviderRequest(
            prompt = prompt,
            identity = identity,
            activeMemories = activeMemories,
            currentProject = currentProject,
            userSettings = userSettings,
            history = history,
            allowProposals = allowProposals
        ).forCloud(sharePersonalContext).copy(
            openTodos = sharedTodos.take(CloudTodos.MAX),
            openTodosTotal = if (sharedTodos.isEmpty()) 0 else maxOf(sharedTodosTotal, sharedTodos.take(CloudTodos.MAX).size),
            sharedProject = sharedProject
        )

        var streamedText = ""
        var failure: String? = null
        var completed: AiProviderEvent.Completed? = null
        // catch{} only sees provider (upstream) failures, never exceptions from our own collector,
        // which keeps Kotlin's flow exception rules intact (audit FB-07).
        cloudEngine.generateStream(providerRequest)
            .catch { e ->
                // A cancelled chat/request must stop; it must not launch fallback work.
                if (e is CancellationException) throw e
                emit(AiProviderEvent.Error(e.message ?: e.javaClass.simpleName))
            }
            .collect { event ->
                when (event) {
                    is AiProviderEvent.Content -> {
                        streamedText = event.text
                        emit(HybridAiState.Streaming(event.text, AiModelUsed.CLOUD))
                    }
                    is AiProviderEvent.Completed -> completed = event
                    is AiProviderEvent.Error -> failure = event.message
                }
            }

        val done = completed
        when {
            done != null -> {
                // Never keyword-scanned cards under a cloud reply (audit SEC-07 / FB-06). The only card is
                // one Claude itself proposed with a relay tool, after every field passed the phone's checks.
                val proposal = done.toolCall?.takeIf { allowProposals }?.let { CloudProposals.toAction(it, projects) }
                val card = (proposal as? CloudProposals.Result.Card)?.action
                val dropped = (proposal as? CloudProposals.Result.Dropped)?.let {
                    " Claude suggested a change, but it was not shown because ${it.problem}."
                }.orEmpty()
                val body = done.fullText.ifBlank {
                    if (card != null) "I've suggested a change below. Nothing happens unless you approve it."
                    else "Claude suggested a change that Aura couldn't show."
                }
                // The relay caps how long a reply may be; say so instead of showing a cut-off answer as whole.
                val text = if (done.cutOff) "$body\n\n\u26a0 $CUT_OFF_NOTE" else body
                val cutOffNote = if (done.cutOff) " The reply reached the length limit and was cut off." else ""
                emit(
                    HybridAiState.Completed(
                        fullText = text,
                        modelUsed = AiModelUsed.CLOUD,
                        latencyMs = done.latencyMs,
                        reasoningRationale = "Answered by ${done.modelName ?: "Claude"} via your Aura relay. " +
                            sentSummary(history, providerRequest) + dropped + cutOffNote,
                        proposedAction = card
                    )
                )
            }
            streamedText.isNotBlank() -> {
                // Keep what arrived, and say plainly that it is incomplete.
                emit(
                    HybridAiState.Completed(
                        fullText = "$streamedText\n\n\u26a0 This answer is incomplete: ${failure ?: "the stream ended early"}",
                        modelUsed = AiModelUsed.CLOUD,
                        latencyMs = 0L,
                        reasoningRationale = "Claude's reply was cut off: ${failure ?: "the stream ended early"}",
                        proposedAction = null
                    )
                )
            }
            mode == HybridRoutingMode.FORCE_CLOUD -> {
                emit(notice(
                    "Claude couldn't answer this message.\n\n${failure ?: "No reply was received."}",
                    "Cloud request failed: ${failure ?: "no reply"}. Your message was sent to the relay."
                ))
            }
            else -> {
                // Auto mode: fall back on this phone, and label it as a fallback.
                val localFallback = localEngine.processQuery(prompt, identity, activeMemories, projects, currentProject, userSettings)
                emit(
                    HybridAiState.Completed(
                        fullText = "\u26a0 Claude is unavailable (${failure ?: "no reply"}). Offline reply:\n\n${localFallback.responseText}",
                        modelUsed = AiModelUsed.LOCAL,
                        latencyMs = localFallback.latencyMs,
                        reasoningRationale = "Cloud request failed (${failure ?: "no reply"}), so Aura answered on this phone. " +
                            "Your message was sent to the relay before it failed.",
                        proposedAction = localFallback.proposedAction
                    )
                )
            }
        }
    }

    /** Says exactly what went to the cloud, for the message's details sheet. */
    internal fun sentSummary(history: List<ChatTurn>, sent: AiProviderRequest): String {
        val earlier = history.count { it.role == ChatTurn.Role.USER }
        val parts = mutableListOf(
            if (earlier == 0) "this message" else
                "this message and the last $earlier earlier Claude exchange${if (earlier == 1) "" else "s"} in this chat",
            "the assistant's name and personality"
        )
        if (sent.allowPersonalContext) {
            val n = sent.activeMemories.size
            if (n > 0) parts += "$n memor${if (n == 1) "y" else "ies"} you marked to share"
            if (sent.userSettings != null) parts += "your name and main interest"
        }
        if (sent.openTodos.isNotEmpty()) {
            val n = sent.openTodos.size
            parts += "$n open to-do${if (n == 1) "" else "s"}" +
                (if (sent.openTodosTotal > n) " of ${sent.openTodosTotal}" else "") + " (titles and due dates)"
        }
        val project = sent.sharedProject
        if (project != null) {
            val contents = mutableListOf("description")
            if (project.milestones.isNotEmpty()) contents += plural(project.milestones.size, "milestone")
            if (project.openTodos.isNotEmpty()) contents += plural(project.openTodos.size, "open to-do")
            if (project.notes.isNotEmpty()) {
                val clipped = project.notes.count { it.clipped }
                contents += plural(project.notes.size, "note") + (if (clipped > 0) " ($clipped shortened)" else "")
            }
            parts += "the project \"${project.title}\" you shared (${contents.joinToString(", ")})" +
                (if (project.notesLeftOut.isNotEmpty()) "; ${plural(project.notesLeftOut.size, "note")} didn't fit and stayed on the phone" else "")
        }
        val projects = if (project != null) "your other projects" else "projects"
        val notSent = if (sent.allowPersonalContext)
            "Not sent: memories you didn't mark, $projects, custom directives."
        else
            "Not sent: memories, $projects, profile, custom directives."
        return "Sent: ${parts.joinToString(", ")}. $notSent"
    }

    /** A status message from Aura itself, not an AI answer. */
    private fun notice(text: String, rationale: String) = HybridAiState.Completed(
        fullText = text,
        modelUsed = AiModelUsed.NONE,
        latencyMs = 0L,
        reasoningRationale = rationale,
        proposedAction = null
    )

    private fun plural(n: Int, word: String) = "$n $word${if (n == 1) "" else "s"}"

    internal companion object {
        const val CUT_OFF_NOTE = "This answer was cut off because it reached the length limit. Ask Claude to continue."

        /** Typed commands handled on the phone in Auto mode (see [shouldRouteToLocal]). */
        val LOCAL_COMMANDS = listOf(
            "remember that ", "remember:", "remember i ", "remember my ",
            "add task:", "add task ", "todo:", "task:",
            "create project", "new project"
        )
    }
}
