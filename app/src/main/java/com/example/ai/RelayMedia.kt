package com.example.ai

import com.example.auth.RelayTokenSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** An image or video Claude asked for (with the generate_image / generate_video tools). */
sealed interface MediaRequest {
    val prompt: String

    /** [fromAttachedImage]: edit the picture attached to the message. [sticker]: transparent PNG sticker. */
    data class Image(
        override val prompt: String,
        val shape: String,
        val fromAttachedImage: Boolean = false,
        val sticker: Boolean = false
    ) : MediaRequest

    /** [fromAttachedImage]: the picture attached to the message becomes the first frame. */
    data class Video(override val prompt: String, val seconds: Int, val shape: String, val fromAttachedImage: Boolean = false) : MediaRequest

    companion object {
        const val MAX_PROMPT_CHARS = 4000
        val IMAGE_SHAPES = setOf("square", "landscape", "portrait")
        val VIDEO_SHAPES = setOf("portrait", "landscape")
        val VIDEO_SECONDS = setOf(4, 8, 12)
        /** The video sizes the relay asks for, by shape (a first frame must match exactly). */
        val VIDEO_SIZES = mapOf("portrait" to (720 to 1280), "landscape" to (1280 to 720))
        val TOOL_NAMES = setOf("generate_image", "generate_video")

        /** Reads Claude's tool call; null if it isn't one of these tools or its fields are wrong. */
        fun fromToolCall(call: ToolCall): MediaRequest? {
            val input = runCatching { JSONObject(call.inputJson) }.getOrNull() ?: return null
            val prompt = input.optString("prompt").trim().takeIf { it.isNotEmpty() && it.length <= MAX_PROMPT_CHARS } ?: return null
            return when (call.name) {
                "generate_image" -> Image(
                    prompt,
                    input.optString("shape").takeIf { it in IMAGE_SHAPES } ?: "square",
                    fromAttachedImage = input.optBoolean("from_attached_image", false),
                    sticker = input.optBoolean("sticker", false)
                )
                "generate_video" -> {
                    val seconds = input.optString("seconds").toIntOrNull()?.takeIf { it in VIDEO_SECONDS } ?: 4
                    Video(
                        prompt, seconds, input.optString("shape").takeIf { it in VIDEO_SHAPES } ?: "portrait",
                        fromAttachedImage = input.optBoolean("from_attached_image", false)
                    )
                }
                else -> null
            }
        }
    }
}

/**
 * Creates images and videos with OpenAI, through Aura's relay (which holds the OpenAI key and fixes
 * the model, quality and limits). Every call returns a readable reason when it fails.
 */
class RelayMedia(
    private val relayUrl: String,
    private val tokens: RelayTokenSource,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        // Making an image can take a minute or two.
        .readTimeout(150, TimeUnit.SECONDS)
        .callTimeout(4, TimeUnit.MINUTES)
        .build()
) {
    /** A video being made: [ticket] proves to the relay that it is this user's. */
    data class VideoJob(val id: String, val ticket: String)

    data class VideoStatus(val status: String, val progress: Int, val error: String?) {
        val done get() = status == "completed"
        val failed get() = status == "failed"
    }

    class MediaError(message: String) : Exception(message)

    /** A made image: JPEG, or PNG for a sticker. */
    class Made(val bytes: ByteArray, val png: Boolean)

    /** Makes an image (a transparent PNG with [sticker]). */
    suspend fun generateImage(prompt: String, shape: String, sticker: Boolean = false): Result<Made> = call {
        val body = JSONObject().put("prompt", prompt).put("shape", shape).apply { if (sticker) put("sticker", true) }
        made(execute(post("/v1/image", body)))
    }

    /** Edits [image] (JPEG or PNG) as [prompt] says; with [sticker], cuts it out as a transparent sticker. */
    suspend fun editImage(prompt: String, image: ByteArray, sticker: Boolean = false): Result<Made> = call {
        val body = withImage(JSONObject().put("prompt", prompt), image).apply { if (sticker) put("sticker", true) }
        made(execute(post("/v1/image/edit", body)))
    }

    private fun made(res: Response): Made = res.use {
        val json = JSONObject(it.body?.string().orEmpty())
        val b64 = json.optString("image").takeIf { s -> s.isNotEmpty() } ?: throw MediaError("no image came back")
        Made(android.util.Base64.decode(b64, android.util.Base64.DEFAULT), json.optString("mediaType") == "image/png")
    }

    private fun withImage(body: JSONObject, image: ByteArray): JSONObject = body
        .put("image", android.util.Base64.encodeToString(image, android.util.Base64.NO_WRAP))
        .put("imageType", ClaudeRelayProvider.imageType(image))

    /**
     * Starts making a video; it takes a few minutes. [firstFrame], if given, must already be the
     * video's exact size (see ChatImages.fitExactly).
     */
    suspend fun startVideo(prompt: String, seconds: Int, shape: String, firstFrame: ByteArray? = null): Result<VideoJob> = call {
        val body = JSONObject().put("prompt", prompt).put("seconds", seconds.toString()).put("shape", shape)
            .apply { if (firstFrame != null) withImage(this, firstFrame) }
        execute(post("/v1/video", body)).use { res ->
            val json = JSONObject(res.body?.string().orEmpty())
            VideoJob(json.optString("id"), json.optString("ticket")).takeIf { it.id.isNotEmpty() && it.ticket.isNotEmpty() }
                ?: throw MediaError("the relay didn't return a video ID")
        }
    }

    suspend fun videoStatus(job: VideoJob): Result<VideoStatus> = call {
        execute(get("/v1/video/status", job)).use { res ->
            val json = JSONObject(res.body?.string().orEmpty())
            VideoStatus(
                json.optString("status"),
                json.optInt("progress").coerceIn(0, 100),
                json.optString("error").takeIf { it.isNotBlank() }
            )
        }
    }

    /** Downloads a finished video into [target]. */
    suspend fun downloadVideo(job: VideoJob, target: File): Result<Unit> = call {
        execute(get("/v1/video/content", job)).use { res ->
            val body = res.body ?: throw MediaError("no video came back")
            body.byteStream().use { input -> target.outputStream().use { input.copyTo(it) } }
            if (target.length() == 0L) throw MediaError("no video came back")
        }
    }

    private suspend fun <T> call(block: suspend () -> T): Result<T> = withContext(Dispatchers.IO) {
        try {
            if (relayUrl.isBlank()) throw MediaError("the relay isn't set up in this build")
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: MediaError) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(MediaError(e.message ?: e.javaClass.simpleName))
        }
    }

    private suspend fun post(path: String, body: JSONObject): Request = Request.Builder()
        .url(relayUrl.trimEnd('/') + path)
        .header("Authorization", "Bearer ${tokens.idToken()}")
        .post(body.toString().toRequestBody("application/json".toMediaType()))
        .build()

    private suspend fun get(path: String, job: VideoJob): Request = Request.Builder()
        .url((relayUrl.trimEnd('/') + path).toHttpUrl().newBuilder().addQueryParameter("id", job.id).addQueryParameter("ticket", job.ticket).build())
        .header("Authorization", "Bearer ${tokens.idToken()}")
        .get()
        .build()

    private fun execute(request: Request): Response {
        val res = client.newCall(request).execute()
        if (!res.isSuccessful) {
            val message = runCatching {
                JSONObject(res.body?.string().orEmpty()).optJSONObject("error")?.optString("message")
            }.getOrNull()?.takeIf { it.isNotBlank() } ?: "HTTP ${res.code}"
            res.close()
            throw MediaError(message)
        }
        return res
    }
}
