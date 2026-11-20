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

        // Claude gets the link-opening tool only when there is a link it could open: the tool can
        // fetch only addresses already in the conversation.
        val readLinks = request.allowLinkReading && ChatLinks.present(request)
        val body = JSONObject()
            .put(
                "system",
                AuraPrompt.system(
                    request.identity, request.activeMemories, request.currentProject, request.userSettings,
                    proposalsEnabled = request.allowProposals,
                    today = if (request.allowProposals || request.openTodos.isNotEmpty()) com.example.data.model.TodoRules.formatDue(com.example.data.model.TodoRules.today()) else null,
                    openTodos = request.openTodos,
                    openTodosTotal = request.openTodosTotal,
                    sharedProject = request.sharedProject,
                    webSearch = request.allowWebSearch,
                    imagesAttached = request.images.size,
                    linkReading = readLinks,
                    media = request.allowMedia
                )
            )
            .put("messages", messagesJson(request))
            .apply { if (request.allowProposals) put("actions", true) }
            .apply { if (request.allowWebSearch) put("web", true) }
            .apply { if (readLinks) put("fetch", true) }
            .apply { if (request.allowMedia) put("media", true) }
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
            // At most one proposal per reply: only the first is kept, later ones are ignored. (The relay also
            // asks Claude for one, except with web search on, where the Claude API doesn't allow that.)
            var toolIndex: Int? = null
            var toolName: String? = null
            val toolInput = StringBuilder()
            var toolCall: ToolCall? = null
            var stopReason: String? = null
            // Web search: the queries Claude ran, the pages it cited, and the block being read.
            val searches = mutableListOf<String>()
            val sources = LinkedHashMap<String, WebSource>()
            var searchIndex: Int? = null
            val searchInput = StringBuilder()
            // Links opened: the block being read and the addresses so far.
            val linksRead = mutableListOf<String>()
            var fetchIndex: Int? = null
            val fetchInput = StringBuilder()
            var afterNonText = false
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
                    is SseEvent.TextStart -> {
                        // Text written after a search starts a new paragraph ("I'll look that up." /
                        // the answer). Text blocks split only by citations join as they are.
                        if (afterNonText && text.isNotEmpty() && !text.last().isWhitespace()) text.append("\n\n")
                        afterNonText = false
                    }
                    is SseEvent.OtherBlockStart -> afterNonText = true
                    is SseEvent.SearchStart -> {
                        afterNonText = true
                        searchIndex = event.index
                        searchInput.setLength(0)
                    }
                    is SseEvent.FetchStart -> {
                        afterNonText = true
                        fetchIndex = event.index
                        fetchInput.setLength(0)
                    }
                    is SseEvent.Citation -> if (event.url.startsWith("https://") || event.url.startsWith("http://")) {
                        sources.putIfAbsent(event.url, WebSource(event.url, event.title.ifBlank { event.url }))
                    }
                    is SseEvent.Error -> {
                        emit(AiProviderEvent.Error("Claude stopped with an error: ${event.message}"))
                        return@flow
                    }
                    is SseEvent.ToolStart -> if (toolIndex == null && toolCall == null) {
                        toolIndex = event.index
                        toolName = event.name
                    }
                    is SseEvent.ToolInput -> when (event.index) {
                        toolIndex -> toolInput.append(event.partialJson)
                        searchIndex -> searchInput.append(event.partialJson)
                        fetchIndex -> fetchInput.append(event.partialJson)
                        else -> Unit
                    }
                    is SseEvent.BlockStop -> when (event.index) {
                        toolIndex -> {
                            toolCall = ToolCall(toolName.orEmpty(), toolInput.toString())
                            toolIndex = null
                        }
                        searchIndex -> {
                            val query = runCatching { JSONObject(searchInput.toString()).optString("query") }.getOrNull()
                                ?.trim()?.takeIf { it.isNotEmpty() }
                            if (query != null) {
                                searches += query.take(MAX_QUERY_CHARS)
                                emit(AiProviderEvent.Searching(query.take(MAX_QUERY_CHARS)))
                            }
                            searchIndex = null
                        }
                        fetchIndex -> {
                            val url = runCatching { JSONObject(fetchInput.toString()).optString("url") }.getOrNull()
                                ?.trim()?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
                            if (url != null) {
                                linksRead += url.take(MAX_LINK_CHARS)
                                emit(AiProviderEvent.ReadingLink(url.take(MAX_LINK_CHARS)))
                            }
                            fetchIndex = null
                        }
                        else -> Unit
                    }
                    is SseEvent.StopReason -> if (event.reason != null) stopReason = event.reason
                    SseEvent.Stop -> {
                        if (text.isBlank() && toolCall == null && stopReason == "pause_turn") {
                            emit(AiProviderEvent.Error("The web search took too long and Claude didn't get to write an answer. Try asking again, perhaps more specifically."))
                        } else if (text.isBlank() && toolCall == null) {
                            emit(AiProviderEvent.Error("Claude returned an empty reply."))
                        } else {
                            emit(
                                AiProviderEvent.Completed(
                                    text.toString(), clock() - startedAt, toolCall = toolCall,
                                    cutOff = stopReason == "max_tokens",
                                    webSearches = searches.toList(),
                                    sources = sources.values.toList(),
                                    searchCutShort = stopReason == "pause_turn",
                                    linksRead = linksRead.toList()
                                )
                            )
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
        /** A text block starts (a reply can have several, e.g. before and after a web search). */
        data class TextStart(val index: Int) : SseEvent
        /** Claude starts a web search; its query arrives as [ToolInput] for the same index. */
        data class SearchStart(val index: Int) : SseEvent
        data class FetchStart(val index: Int) : SseEvent
        /** Any other non-text block (search results, filtering code) starts. */
        data class OtherBlockStart(val index: Int) : SseEvent
        /** Claude cited a web page in the text it is writing. */
        data class Citation(val url: String, val title: String) : SseEvent
        /** Why the model stopped ("end_turn", "tool_use", "max_tokens", "pause_turn"...), sent just before [Stop]. */
        data class StopReason(val reason: String?) : SseEvent
        data object Stop : SseEvent
    }

    companion object {
        private val JSON = "application/json".toMediaType()

        /** Longest search query kept for showing to the user. */
        const val MAX_QUERY_CHARS = 200
        /** Longest link address kept for showing to the user. */
        const val MAX_LINK_CHARS = 500

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(3, TimeUnit.MINUTES)
            .build()

        /** Earlier turns, oldest first, then the new message. */
        fun messagesJson(request: AiProviderRequest): JSONArray {
            val array = JSONArray()
            request.history.forEach { turn ->
                array.put(JSONObject().put("role", turn.role.wire).put("content", turn.text))
            }
            if (request.images.isEmpty()) {
                array.put(JSONObject().put("role", "user").put("content", request.prompt))
            } else {
                // The images first, in the order picked, then the question about them (if any).
                val blocks = JSONArray()
                request.images.forEach { image ->
                    blocks.put(
                        JSONObject().put("type", "image").put(
                            "source",
                            JSONObject().put("type", "base64").put("media_type", "image/jpeg")
                                .put("data", android.util.Base64.encodeToString(image, android.util.Base64.NO_WRAP))
                        )
                    )
                }
                val text = listOfNotNull(request.attachmentNote?.takeIf { it.isNotBlank() }, request.prompt.takeIf { it.isNotBlank() })
                    .joinToString("\n\n")
                if (text.isNotEmpty()) blocks.put(JSONObject().put("type", "text").put("text", text))
                array.put(JSONObject().put("role", "user").put("content", blocks))
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
                "content_block_start" -> obj.optJSONObject("content_block")?.let { block ->
                    when (block.optString("type")) {
                        "tool_use" -> SseEvent.ToolStart(index, block.optString("name"))
                        "text" -> SseEvent.TextStart(index)
                        "server_tool_use" -> when (block.optString("name")) {
                            "web_search" -> SseEvent.SearchStart(index)
                            "web_fetch" -> SseEvent.FetchStart(index)
                            else -> SseEvent.OtherBlockStart(index)
                        }
                        "thinking", "redacted_thinking" -> null
                        else -> SseEvent.OtherBlockStart(index)
                    }
                }
                "content_block_delta" -> {
                    val delta = obj.optJSONObject("delta")
                    when (delta?.optString("type")) {
                        "text_delta" -> SseEvent.TextDelta(delta.optString("text"))
                        "input_json_delta" -> SseEvent.ToolInput(index, delta.optString("partial_json"))
                        "citations_delta" -> delta.optJSONObject("citation")
                            ?.takeIf { it.optString("type") == "web_search_result_location" }
                            ?.let { c -> SseEvent.Citation(c.optString("url"), if (c.isNull("title")) "" else c.optString("title").trim()) }
                        else -> null
                    }
                }
                "content_block_stop" -> SseEvent.BlockStop(index)
                "message_delta" -> obj.optJSONObject("delta")
                    ?.takeUnless { it.isNull("stop_reason") }?.optString("stop_reason")
                    ?.takeIf { it.isNotBlank() }?.let { SseEvent.StopReason(it) }
                "message_stop" -> SseEvent.Stop
                "error" -> SseEvent.Error(obj.optJSONObject("error")?.optString("message")?.ifBlank { null } ?: "unknown error")
                else -> null
            }
        }
    }
}
