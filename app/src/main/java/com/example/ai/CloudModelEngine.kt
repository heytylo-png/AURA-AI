package com.example.ai

import android.util.Log
import com.example.data.model.AiIdentityConfig
import com.example.data.model.MemoryEntity
import com.example.data.model.ProjectEntity
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout

import com.example.data.model.UserSettingsEntity

private const val TAG = "CloudModelEngine"

sealed interface CloudAiStreamChunk {
    data class Content(val text: String) : CloudAiStreamChunk
    data class Completed(val fullText: String, val latencyMs: Long) : CloudAiStreamChunk
    data class Error(val message: String) : CloudAiStreamChunk
}

class CloudModelEngine {

    // Lazy initialization mandated by Firebase AI Logic skill to prevent early startup crashes
    private val model by lazy {
        Firebase.ai.generativeModel("gemini-3.8-flash")
    }

    /**
     * Builds a comprehensive prompt with Aura's custom identity, persistent memory facts,
     * active project context, and personalized user profile settings stored in Room Database.
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

    /**
     * Streams content from Gemini 3.8 Flash with 8s timeout and cooperative cancellation.
     */
    fun generateContentStream(
        prompt: String,
        identity: AiIdentityConfig,
        activeMemories: List<MemoryEntity>,
        currentProject: ProjectEntity?,
        userSettings: UserSettingsEntity? = null
    ): Flow<CloudAiStreamChunk> = flow {
        val fullPrompt = buildContextualPrompt(prompt, identity, activeMemories, currentProject, userSettings)
        val startTime = System.currentTimeMillis()
        val accumulatedText = StringBuilder()

        try {
            // Responsive timeout for initial cloud connection & streaming (8s)
            withTimeout(8_000L) {
                model.generateContentStream(fullPrompt).collect { chunk ->
                    val chunkText = chunk.text.orEmpty()
                    if (chunkText.isNotEmpty()) {
                        accumulatedText.append(chunkText)
                        emit(CloudAiStreamChunk.Content(accumulatedText.toString()))
                    }
                }
            }

            val finalOutput = accumulatedText.toString()
            if (finalOutput.isNotBlank()) {
                val totalTime = System.currentTimeMillis() - startTime
                emit(CloudAiStreamChunk.Completed(finalOutput, totalTime))
            } else {
                emit(CloudAiStreamChunk.Error("Gemini 3.8 Flash returned an empty response."))
            }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "Cloud generation timed out after 8s; seamlessly delegating to local model.")
            emit(CloudAiStreamChunk.Error("Cloud connection timed out after 8s."))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Firebase AI generation error: ${e.message}")
            val msg = e.localizedMessage ?: "Cloud AI model failed to connect."
            emit(CloudAiStreamChunk.Error(msg))
        }
    }
}
