package com.example.ai

import com.example.data.model.AiIdentityConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * OpenAI adapter that talks ONLY to Aura's trusted backend.
 *
 * The backend owns the OpenAI API key and selects the actual OpenAI model.
 * Never pass an OpenAI API key to this class or store one in the Android app.
 *
 * Expected backend contract:
 * POST {backendBaseUrl}/v1/aura/chat/completions
 * JSON: { "stream": true, "messages": [{ "role": "system", "content": "..." },
 *       { "role": "user", "content": "..." }] }
 * Response: OpenAI-compatible Server-Sent Events ending with data: [DONE].
 */
class OpenAiProvider(
    backendBaseUrl: String,
    private val httpClient: OkHttpClient = OkHttpClient()
) : AiProvider {

    private val endpoint: HttpUrl = requireNotNull(backendBaseUrl.toHttpUrlOrNull()) {
        "Aura backend URL is invalid."
    }.also { url ->
        require(url.isHttps) { "Aura backend must use HTTPS." }
        require(url.username.isEmpty() && url.password.isEmpty() &&
            url.query == null && url.fragment == null) {
            "Aura backend URL must not contain credentials, query parameters, or fragments."
        }
    }.newBuilder()
        .addPathSegments("v1/aura/chat/completions")
        .build()

    override val providerId: String = "openai"
    override val capabilities = AiProviderCapabilities(
        streaming = true,
        supportsPersonalContext = false,
        supportsOffline = false
    )

    override fun generateStream(request: AiProviderRequest): Flow<AiProviderEvent> = flow {
        // This adapter deliberately has no personal-context capability. Fail closed
        // if a future caller bypasses HybridAiEngine's current sanitization.
        if (request.allowPersonalContext || request.activeMemories.isNotEmpty() ||
            request.currentProject != null || request.userSettings != null) {
            emit(AiProviderEvent.Error("OpenAI cloud requests cannot include personal context."))
            return@flow
        }

        val payload = JSONObject()
            .put("stream", true)
            .put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", identityPrompt(request.identity)))
                put(JSONObject().put("role", "user").put("content", request.prompt))
            })
            .toString()

        val httpRequest = Request.Builder()
            .url(endpoint)
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("Accept", "text/event-stream")
            .build()
        val call = httpClient.newCall(httpRequest)
        val job = currentCoroutineContext()[Job]
        val cancellation = job?.invokeOnCompletion { cause ->
            if (cause is CancellationException) call.cancel()
        }
        val startedAt = System.currentTimeMillis()
        val accumulated = StringBuilder()

        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    emit(AiProviderEvent.Error("Aura backend returned HTTP ${response.code}."))
                    return@flow
                }
                val source = response.body?.source()
                if (source == null) {
                    emit(AiProviderEvent.Error("Aura backend returned an empty response."))
                    return@flow
                }

                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data == "[DONE]") break
                    if (data.isEmpty()) continue

                    val delta = runCatching {
                        JSONObject(data)
                            .optJSONArray("choices")
                            ?.optJSONObject(0)
                            ?.optJSONObject("delta")
                            ?.optString("content")
                            .orEmpty()
                    }.getOrElse {
                        emit(AiProviderEvent.Error("Aura backend sent an invalid stream event."))
                        return@flow
                    }

                    if (delta.isNotEmpty()) {
                        accumulated.append(delta)
                        emit(AiProviderEvent.Content(accumulated.toString()))
                    }
                }
            }

            if (accumulated.isNotBlank()) {
                emit(AiProviderEvent.Completed(accumulated.toString(), System.currentTimeMillis() - startedAt))
            } else {
                emit(AiProviderEvent.Error("OpenAI returned an empty response."))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: IOException) {
            emit(AiProviderEvent.Error("Could not reach Aura's AI backend."))
        } catch (failure: Exception) {
            emit(AiProviderEvent.Error("OpenAI request failed."))
        } finally {
            cancellation?.dispose()
        }
    }.flowOn(Dispatchers.IO)

    internal fun identityPrompt(identity: AiIdentityConfig): String = """
        You are ${identity.name}, Aura's AI assistant.
        Personality: ${identity.archetype.title}.
        Guidance: ${identity.archetype.traitPrompt}
        Core values: ${identity.coreValues}
        Response style: ${identity.responseLength.label}
        Additional instructions: ${identity.customDirectives}
        When proposing actions, clearly present them for the user to approve before execution.
    """.trimIndent()
}
