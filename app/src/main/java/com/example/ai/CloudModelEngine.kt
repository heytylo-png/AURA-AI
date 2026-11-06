package com.example.ai

import android.util.Log
import com.example.data.model.AiIdentityConfig
import com.example.data.model.MemoryEntity
import com.example.data.model.ProjectEntity
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.timeout
import kotlin.time.Duration.Companion.seconds
import com.example.data.model.UserSettingsEntity

private const val TAG = "CloudModelEngine"

sealed interface CloudAiStreamChunk {
    data class Content(val text: String) : CloudAiStreamChunk
    data class Completed(val fullText: String, val latencyMs: Long) : CloudAiStreamChunk
    data class Error(val message: String) : CloudAiStreamChunk
}

/** Gemini is the first adapter behind Aura's provider-neutral contract. */
class CloudModelEngine : AiProvider {

    override val providerId: String = "gemini"
    override val capabilities = AiProviderCapabilities(
        streaming = true,
        supportsPersonalContext = true,
        supportsOffline = false
    )

    // Lazy initialization prevents early startup crashes.
    private val model by lazy {
        Firebase.ai.generativeModel("gemini-3.8-flash")
    }

    override fun generateStream(request: AiProviderRequest): Flow<AiProviderEvent> = flow {
        // Personal context is denied unless this specific request carries explicit consent.
        val memories = if (request.allowPersonalContext) request.activeMemories else emptyList()
        val project = if (request.allowPersonalContext) request.currentProject else null
        val settings = if (request.allowPersonalContext) request.userSettings else null

        generateContentStream(
            prompt = request.prompt,
            identity = request.identity,
            activeMemories = memories,
            currentProject = project,
            userSettings = settings
        ).collect { chunk ->
            when (chunk) {
                is CloudAiStreamChunk.Content -> emit(AiProviderEvent.Content(chunk.text))
                is CloudAiStreamChunk.Completed -> emit(AiProviderEvent.Completed(chunk.fullText, chunk.latencyMs))
                is CloudAiStreamChunk.Error -> emit(AiProviderEvent.Error(chunk.message))
            }
        }
    }

    /**
     * Builds Aura's identity prompt. Personal context parameters must already
     * have passed the request-level consent gate above.
     */
    fun buildContextualPrompt(
        userMessage: String,
        identity: AiIdentityConfig,
        activeMemories: List<MemoryEntity>,
        currentProject: ProjectEntity?,
        userSettings: UserSettingsEntity? = null
    ): String {
        val userProfileBlock = if (userSettings != null && (userSettings.userName.isNotBlank() || userSettings.primaryInterest.isNotBlank())) {
            val nameLine = if (userSettings.userName.isNotBlank()) "User Name: ${userSettings.userName.trim()}\n" else ""
            val interestLine = if (userSettings.primaryInterest.isNotBlank()) "Primary Interest: ${userSettings.primaryInterest.trim()}\n" else ""
            val nameGuidance = if (userSettings.userName.isNotBlank()) "address the user by their name (${userSettings.userName.trim()}) naturally, and " else ""
            val interestGuidance = if (userSettings.primaryInterest.isNotBlank()) "tailor insights, recommendations, and examples to their primary interest (${userSettings.primaryInterest.trim()})." else "provide helpful answers."
            "\n[USER PERSONALIZATION SETTINGS]\n$nameLine$interestLine" +
                "Personalization Directive: Always $nameGuidance$interestGuidance\n"
        } else ""

        val memoryBlock = if (activeMemories.isNotEmpty()) {
            val list = activeMemories.take(12).joinToString("\n") { "• [${it.category.name}] ${it.content}" }
            "\n[PERSISTENT USER MEMORIES & PREFERENCES]\n$list\n"
        } else ""

        val projectBlock = if (currentProject != null) {
            "\n[ACTIVE PROJECT CONTEXT]\nTitle: ${currentProject.title}\nStatus: ${currentProject.status.label}\nDescription: ${currentProject.description}\n"
        } else ""

        return """
System: You are ${identity.name}, an advanced hybrid personal AI with your own distinct identity, values, and memory.
Personality Archetype: ${identity.archetype.title}
Directive: ${identity.archetype.traitPrompt}
Core Values: ${identity.coreValues}
Style: ${identity.responseLength.label}
Special Directive: ${identity.customDirectives}
Autonomy Guardrail: When proposing actions, to-dos, or milestones, specify them clearly as an action proposal so the user can approve them before execution.$userProfileBlock
$memoryBlock$projectBlock
User Query: $userMessage
""".trimIndent()
    }

    @OptIn(FlowPreview::class)
    fun generateContentStream(
        prompt: String,
        identity: AiIdentityConfig,
        activeMemories: List<MemoryEntity>,
        currentProject: ProjectEntity?,
        userSettings: UserSettingsEntity? = null
    ): Flow<CloudAiStreamChunk> {
        val fullPrompt = buildContextualPrompt(prompt, identity, activeMemories, currentProject, userSettings)
        val startTime = System.currentTimeMillis()
        val accumulatedText = StringBuilder()

        // Built from operators rather than try/catch around emit(), so exceptions thrown by the
        // collector are never caught here (Kotlin flow exception transparency; audit FB-07).
        return flow { emitAll(model.generateContentStream(fullPrompt)) } // model is created lazily inside the flow
            // No limit on the whole answer: only fail if Gemini goes silent between chunks.
            .timeout(IDLE_TIMEOUT)
            .mapNotNull { chunk ->
                chunk.text?.takeIf { it.isNotEmpty() }?.let {
                    accumulatedText.append(it)
                    CloudAiStreamChunk.Content(accumulatedText.toString()) as CloudAiStreamChunk
                }
            }
            .onCompletion { cause ->
                if (cause == null) {
                    val finalOutput = accumulatedText.toString()
                    emit(
                        if (finalOutput.isNotBlank()) {
                            CloudAiStreamChunk.Completed(finalOutput, System.currentTimeMillis() - startTime)
                        } else {
                            CloudAiStreamChunk.Error("Gemini returned an empty response.")
                        }
                    )
                }
            }
            .catch { e ->
                if (e is TimeoutCancellationException) {
                    Log.w(TAG, "Gemini went silent for ${IDLE_TIMEOUT.inWholeSeconds}s.")
                    emit(CloudAiStreamChunk.Error("Gemini stopped responding for ${IDLE_TIMEOUT.inWholeSeconds} seconds."))
                } else {
                    if (e is CancellationException) throw e
                    Log.w(TAG, "Gemini request failed", e)
                    emit(CloudAiStreamChunk.Error(describeFailure(e)))
                }
            }
    }

    companion object {
        private val IDLE_TIMEOUT = 60.seconds

        /** A readable description that keeps the exception type and its causes, for diagnosing setup problems. */
        fun describeFailure(e: Throwable): String {
            val parts = generateSequence(e) { it.cause }.take(4)
                .map { t -> "${t.javaClass.simpleName}${t.message?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""}" }
                .distinct()
                .toList()
            return parts.joinToString(" \u2190 ").take(500)
        }
    }
}
