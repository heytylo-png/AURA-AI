package com.example.voice

import com.example.auth.RelayTokenSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Reads a reply aloud in the user's ElevenLabs voice, through Aura's relay (which holds the
 * ElevenLabs key and fixes the voice). Returns an MP3 file, or a readable reason it failed.
 */
class RelaySpeech(
    private val relayUrl: String,
    private val tokens: RelayTokenSource,
    private val cacheDir: File,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()
) {
    suspend fun fetch(text: String): Result<File> = withContext(Dispatchers.IO) {
        try {
            if (relayUrl.isBlank()) return@withContext Result.failure(SpeechError("the relay isn't set up in this build"))
            val token = tokens.idToken()
            val request = Request.Builder()
                .url(relayUrl.trimEnd('/') + "/v1/speak")
                .header("Authorization", "Bearer $token")
                .post(JSONObject().put("text", text).toString().toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(request).execute().use { res ->
                if (!res.isSuccessful) {
                    val message = runCatching {
                        JSONObject(res.body?.string().orEmpty()).optJSONObject("error")?.optString("message")
                    }.getOrNull()?.takeIf { it.isNotBlank() } ?: "HTTP ${res.code}"
                    return@withContext Result.failure(SpeechError(message))
                }
                val out = File(cacheDir, "aura_reply.mp3")
                res.body?.byteStream()?.use { input -> out.outputStream().use { input.copyTo(it) } }
                    ?: return@withContext Result.failure(SpeechError("no audio came back"))
                if (out.length() == 0L) return@withContext Result.failure(SpeechError("no audio came back"))
                Result.success(out)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(SpeechError(e.message ?: e.javaClass.simpleName))
        }
    }

    class SpeechError(message: String) : Exception(message)
}

/** Turns a reply into the text that gets read aloud: no Markdown, one line, capped at a sentence end. */
object SpeechText {
    /** Must match the relay's MAX_SPEECH_CHARS. */
    const val MAX_CHARS = 1500

    fun prepare(markdown: String, max: Int = MAX_CHARS): String {
        val plain = com.example.ui.components.MarkdownRenderer.plainText(markdown)
            .replace('•', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
        if (plain.length <= max) return plain
        val cut = plain.substring(0, max)
        val sentenceEnd = Regex("[.!?](\\s|$)").findAll(cut).lastOrNull()?.range?.first
        return if (sentenceEnd != null && sentenceEnd > max / 2) cut.substring(0, sentenceEnd + 1)
        else cut.substringBeforeLast(' ').trimEnd() + "…"
    }
}
