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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

enum class HybridRoutingMode(val label: String, val description: String) {
    AUTO_HYBRID("Auto Hybrid Router", "Routes simple & offline tasks to Local, complex reasoning to Cloud"),
    FORCE_LOCAL("Local Model Only", "100% on-device offline edge reasoning"),
    FORCE_CLOUD("Cloud Gemini 3.8 Flash", "Frontier multimodal cloud model for maximum capability")
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
    private val cloudEngine: AiProvider = CloudModelEngine()
) {

    private fun isOnline(): Boolean {
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
        if (wordCount <= 4 && (lower.contains("hi") || lower.contains("hello") || lower.contains("hey"))) return true
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
        userSettings: UserSettingsEntity? = null
    ): Flow<HybridAiState> = flow {
        emit(HybridAiState.Starting)
        val online = isOnline()

        val routeToLocal = when (mode) {
            HybridRoutingMode.FORCE_LOCAL -> true
            HybridRoutingMode.FORCE_CLOUD -> !online // Force cloud unless device is strictly offline
            HybridRoutingMode.AUTO_HYBRID -> !online || shouldRouteToLocal(prompt)
        }

        if (routeToLocal) {
            val localResult = localEngine.processQuery(prompt, identity, activeMemories, projects, currentProject, userSettings)
            val rationale = if (!online) {
                "Offline Mode: Handled via on-device Local Reasoning Engine."
            } else {
                "Auto Router: Fast path executed locally (${localResult.latencyMs}ms)."
            }
            emit(
                HybridAiState.Completed(
                    fullText = localResult.responseText,
                    modelUsed = AiModelUsed.LOCAL,
                    latencyMs = localResult.latencyMs,
                    reasoningRationale = "$rationale ${localResult.reasoningExplanation}",
                    proposedAction = localResult.proposedAction
                )
            )
            return@flow
        }

        // Attempt Cloud Model (Gemini 3.8 Flash) with graceful fallback to Local
        var cloudCompleted = false
        var lastStreamedText = ""

        try {
            cloudEngine.generateStream(
                AiProviderRequest(
                    prompt = prompt,
                    identity = identity,
                    activeMemories = activeMemories,
                    currentProject = currentProject,
                    userSettings = userSettings,
                    // Cloud sharing stays disabled until the user explicitly opts in.
                    allowPersonalContext = false
                )
            ).collect { chunk ->
                    when (chunk) {
                        is AiProviderEvent.Content -> {
                            lastStreamedText = chunk.text
                            emit(HybridAiState.Streaming(chunk.text, AiModelUsed.CLOUD))
                        }
                        is AiProviderEvent.Completed -> {
                            cloudCompleted = true
                            // Check if prompt inspired an action proposal even in cloud
                            val localScan = localEngine.processQuery(prompt, identity, activeMemories, projects, currentProject, userSettings)
                            emit(
                                HybridAiState.Completed(
                                    fullText = chunk.fullText,
                                    modelUsed = AiModelUsed.CLOUD,
                                    latencyMs = chunk.latencyMs,
                                    reasoningRationale = "Cloud Reasoning via Gemini 3.8 Flash (High-depth inference)",
                                    proposedAction = localScan.proposedAction
                                )
                            )
                        }
                        is AiProviderEvent.Error -> {
                            if (lastStreamedText.isBlank()) {
                                val localFallback = localEngine.processQuery(prompt, identity, activeMemories, projects, currentProject, userSettings)
                                emit(
                                    HybridAiState.Completed(
                                        fullText = localFallback.responseText,
                                        modelUsed = AiModelUsed.LOCAL,
                                        latencyMs = localFallback.latencyMs,
                                        reasoningRationale = "Handled locally via edge reasoning engine.",
                                        proposedAction = localFallback.proposedAction
                                    )
                                )
                            } else {
                                emit(
                                    HybridAiState.Completed(
                                        fullText = lastStreamedText,
                                        modelUsed = AiModelUsed.CLOUD,
                                        latencyMs = 8_000L,
                                        reasoningRationale = "Cloud stream concluded.",
                                        proposedAction = null
                                    )
                                )
                            }
                        }
                    }
                }
        } catch (e: Exception) {
            val localFallback = localEngine.processQuery(prompt, identity, activeMemories, projects, currentProject, userSettings)
            emit(
                HybridAiState.Completed(
                    fullText = localFallback.responseText,
                    modelUsed = AiModelUsed.LOCAL,
                    latencyMs = localFallback.latencyMs,
                    reasoningRationale = "Handled locally via edge reasoning engine.",
                    proposedAction = localFallback.proposedAction
                )
            )
        }
    }
}
