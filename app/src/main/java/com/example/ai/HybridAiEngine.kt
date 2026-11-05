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
    AUTO_HYBRID("Auto Hybrid Router", "Routes simple & offline tasks to Local, complex reasoning to Cloud"),
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
    fun shouldRouteToLocal(prompt: String): Boolean {
        val lower = prompt.lowercase().trim()
        val wordCount = prompt.split("\\s+".toRegex()).size

        // Direct triggers for local engine
        // Whole words only: "hi" must not match "this", "which" or "think".
        if (wordCount <= 4 && GREETING.containsMatchIn(lower)) return true
        if (lower.startsWith("remember") || lower.contains("my favorite") || lower.contains("i prefer")) return true
        if (lower.startsWith("create project") || lower.startsWith("new project")) return true
        if (lower.startsWith("add task") || lower.startsWith("todo:") || lower.startsWith("task:")) return true
        if (lower.contains("what do you remember") || lower.contains("show memories")) return true
        if (lower.contains("who are you") || lower.contains("what is your identity")) return true

        return false
    }

    fun processHybridRequest(
        prompt: String,
        mode: HybridRoutingMode,
        identity: AiIdentityConfig,
        activeMemories: List<MemoryEntity>,
        projects: List<ProjectEntity>,
        currentProject: ProjectEntity? = null,
        userSettings: UserSettingsEntity? = null,
        allowCloudRequest: Boolean = false
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
            userSettings = userSettings
        ).withoutPersonalContext()

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
                // Check if the prompt inspired an action proposal even in cloud
                val localScan = localEngine.processQuery(prompt, identity, activeMemories, projects, currentProject, userSettings)
                emit(
                    HybridAiState.Completed(
                        fullText = done.fullText,
                        modelUsed = AiModelUsed.CLOUD,
                        latencyMs = done.latencyMs,
                        reasoningRationale = "Answered by Claude via your Aura relay. Only this message was sent; no memories, projects or profile.",
                        proposedAction = localScan.proposedAction
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

    /** A status message from Aura itself, not an AI answer. */
    private fun notice(text: String, rationale: String) = HybridAiState.Completed(
        fullText = text,
        modelUsed = AiModelUsed.LOCAL,
        latencyMs = 0L,
        reasoningRationale = rationale,
        proposedAction = null
    )

    private companion object {
        val GREETING = Regex("""\b(hi|hello|hey)\b""")
    }
}
