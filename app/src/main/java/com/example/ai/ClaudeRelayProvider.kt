package com.example.ai

import com.example.BuildConfig
import com.example.auth.FirebaseRelayAuth
import com.example.auth.RelayTokenSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.job
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Claude, reached through Aura's relay (relay/ in this repo), which holds the Anthropic API key.
 *
 * Every failure is reported as [AiProviderEvent.Error] with a readable reason; nothing is
 * silently replaced. Timeouts: 15 s to connect, 60 s of silence between streamed chunks,
 * 3 minutes for the whole reply.
 */
class ClaudeRelayProvider(
    private val relayUrl: String,
    private val tokens: RelayTokenSource,
    private val client: OkHttpClient = defaultClient(),
    private val clock: () -> Long = System::currentTimeMillis
) : AiProvider {

    override val providerId: String = "claude"
    override val capabilities = AiProviderCapabilities(
        streaming = true,
        supportsPersonalContext = true,
        supportsOffline = false
    )

    override fun generateStream(request: AiProviderRequest): Flow<AiProviderEvent> = flow {
        if (relayUrl.isBlank()) {
            emit(AiProviderEvent.Error("Claude isn't set up in this build yet: no relay address (AURA_RELAY_URL)."))
            return@flow
        }
        val token = try {
            tokens.idToken()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(AiProviderEvent.Error(e.message ?: "Couldn't sign in to the Claude relay."))
            return@flow
        }

        val body = JSONObject()
            .put(
                "system",
                AuraPrompt.system(
                    request.identity, request.activeMemories, request.currentProject, request.userSettings,
                    proposalsEnabled = request.allowProposals,
                    today = if (request.allowProposals || request.openTodos.isNotEmpty()) com.example.data.model.TodoRules.formatDue(com.example.data.model.TodoRules.today()) else null,
                    openTodos = request.openTodos,
                    openTodosTotal = request.openTodosTotal
                )
            )
            .put("messages", messagesJson(request))
            .apply { if (request.allowProposals) put("actions", true) }
            .toString()

        val startedAt = clock()
        var response = try {
            execute(token, body)
        } catch (e: IOException) {
            emit(AiProviderEvent.Error(e.message ?: "Couldn't reach the Claude relay."))
            return@flow
        }
        if (response.code == 401 && errorType(response) == "token_expired") {
            response.close()
            val fresh = try {
                tokens.idToken(forceRefresh = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emit(AiProviderEvent.Error(e.message ?: "Couldn't refresh the Claude relay sign-in."))
                return@flow
            }
            response = try {
                execute(fresh, body)
            } catch (e: IOException) {
                emit(AiProviderEvent.Error(e.message ?: "Couldn't reach the Claude relay."))
                return@flow
            }
        }

        response.use { res ->
            if (!res.isSuccessful) {
                emit(AiProviderEvent.Error(describeHttpError(res)))
                return@flow
            }
            val source = res.body?.source()
            if (source == null) {
                emit(AiProviderEvent.Error("The Claude relay returned an empty response."))
                return@flow
            }
            val text = StringBuilder()
            // At most one proposal per reply (the relay disables parallel tool use); later ones are ignored.
            var toolIndex: Int? = null
            var toolName: String? = null
            val toolInput = StringBuilder()
            var toolCall: ToolCall? = null
            while (true) {
                currentCoroutineContext().ensureActive()
                val line = try {
                    source.readUtf8Line()
                } catch (e: java.net.SocketTimeoutException) {
                    emit(AiProviderEvent.Error("Claude stopped responding for 60 seconds; the reply is incomplete."))
                    return@flow
                } catch (e: IOException) {
                    currentCoroutineContext().ensureActive()
                    emit(AiProviderEvent.Error("The connection to the Claude relay broke (${e.message ?: e.javaClass.simpleName})."))
                    return@flow
                } ?: break
                when (val event = parseSseLine(line)) {
                    is SseEvent.TextDelta -> {
                        text.append(event.text)
                        emit(AiProviderEvent.Content(text.toString()))
                    }
                    is SseEvent.Error -> {
                        emit(AiProviderEvent.Error("Claude stopped with an error: ${event.message}"))
                        return@flow
                    }
                    is SseEvent.ToolStart -> if (toolIndex == null && toolCall == null) {
                        toolIndex = event.index
                        toolName = event.name
                    }
                    is SseEvent.ToolInput -> if (event.index == toolIndex) toolInput.append(event.partialJson)
                    is SseEvent.BlockStop -> if (event.index == toolIndex) {
                        toolCall = ToolCall(toolName.orEmpty(), toolInput.toString())
                        toolIndex = null
                    }
                    SseEvent.Stop -> {
                        if (text.isBlank() && toolCall == null) {
                            emit(AiProviderEvent.Error("Claude returned an empty reply."))
                        } else {
                            emit(AiProviderEvent.Completed(text.toString(), clock() - startedAt, toolCall = toolCall))
                        }
                        return@flow
                    }
                    null -> Unit
                }
            }
            emit(AiProviderEvent.Error("The connection ended before Claude finished replying."))
        }
    }.flowOn(Dispatchers.IO)

    /** Runs the HTTP call; cancels it if the collecting coroutine is cancelled. */
    private suspend fun execute(token: String, body: String): Response {
        val call: Call = client.newCall(
            Request.Builder()
                .url(relayUrl.trimEnd('/') + "/v1/chat")
                .header("Authorization", "Bearer $token")
                .post(body.toRequestBody(JSON))
                .build()
        )
        val handle = currentCoroutineContext().job.invokeOnCompletion { call.cancel() }
        return try {
            call.execute()
        } catch (e: IOException) {
            handle.dispose()
            throw RelayUnreachable(e)
        }
    }

    private class RelayUnreachable(cause: IOException) :
        IOException("Couldn't reach the Claude relay (${cause.message ?: cause.javaClass.simpleName}). Check your connection.", cause)

    sealed interface SseEvent {
        data class TextDelta(val text: String) : SseEvent
        data class Error(val message: String) : SseEvent
        data class ToolStart(val index: Int, val name: String) : SseEvent
        data class ToolInput(val index: Int, val partialJson: String) : SseEvent
        data class BlockStop(val index: Int) : SseEvent
        data object Stop : SseEvent
    }

    companion object {
        private val JSON = "application/json".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(3, TimeUnit.MINUTES)
            .build()

        /** Earlier turns, oldest first, then the new message. */
        fun messagesJson(request: AiProviderRequest): JSONArray {
            val array = JSONArray()
            (request.history + ChatTurn(ChatTurn.Role.USER, request.prompt)).forEach { turn ->
                array.put(JSONObject().put("role", turn.role.wire).put("content", turn.text))
            }
            return array
        }

        /** The provider used by the app: relay address from the build, anonymous Firebase sign-in. */
        fun create(): ClaudeRelayProvider = ClaudeRelayProvider(BuildConfig.AURA_RELAY_URL, FirebaseRelayAuth())

        private fun errorJson(res: Response): JSONObject? =
            runCatching { JSONObject(res.peekBody(64 * 1024).string()).optJSONObject("error") }.getOrNull()

        private fun errorType(res: Response): String? = errorJson(res)?.optString("type")?.takeIf { it.isNotBlank() }

        fun describeHttpError(res: Response): String {
            val err = errorJson(res)
            val message = err?.optString("message")?.takeIf { it.isNotBlank() }
            val hint = when (err?.optString("type")) {
                "user_not_allowed" -> " Add this access ID to the AURA_ALLOWED_UIDS repository secret, then re-run the relay deployment."
                "relay_not_configured" -> " The relay is missing a setting; re-run its deployment after adding the secrets."
                else -> ""
            }
            return "Claude relay error (HTTP ${res.code}): ${message ?: res.message.ifBlank { "no details" }}.$hint"
        }

        /** Parses one line of the Anthropic Messages stream. Returns null for lines that carry nothing to act on. */
        fun parseSseLine(line: String): SseEvent? {
            if (!line.startsWith("data:")) return null
            val obj = runCatching { JSONObject(line.removePrefix("data:").trim()) }.getOrNull() ?: return null
            val index = obj.optInt("index", -1)
            return when (obj.optString("type")) {
                "content_block_start" -> obj.optJSONObject("content_block")
                    ?.takeIf { it.optString("type") == "tool_use" }
                    ?.let { SseEvent.ToolStart(index, it.optString("name")) }
                "content_block_delta" -> {
                    val delta = obj.optJSONObject("delta")
                    when (delta?.optString("type")) {
                        "text_delta" -> SseEvent.TextDelta(delta.optString("text"))
                        "input_json_delta" -> SseEvent.ToolInput(index, delta.optString("partial_json"))
                        else -> null
                    }
                }
                "content_block_stop" -> SseEvent.BlockStop(index)
                "message_stop" -> SseEvent.Stop
                "error" -> SseEvent.Error(obj.optJSONObject("error")?.optString("message")?.ifBlank { null } ?: "unknown error")
                else -> null
            }
        }
    }
}
