package com.example

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.ai.AiProvider
import com.example.ai.AiProviderCapabilities
import com.example.ai.AiProviderEvent
import com.example.ai.AiProviderRequest
import com.example.ai.ClaudeRelayProvider
import com.example.ai.HybridAiEngine
import com.example.ai.HybridAiState
import com.example.ai.HybridRoutingMode
import com.example.ai.MediaRequest
import com.example.ai.RelayMedia
import com.example.ai.ToolCall
import com.example.auth.RelayTokenSource
import com.example.data.model.AiIdentityConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Images and videos Claude asks for: reading the request, routing it, and the relay calls. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MediaTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    private val tokens = object : RelayTokenSource {
        override fun accessId(): String = "uid"
        override suspend fun idToken(forceRefresh: Boolean): String = "token"
    }

    @Test fun `Claude's tool calls are read with checked fields only`() {
        assertEquals(
            MediaRequest.Image("a red bike", "landscape"),
            MediaRequest.fromToolCall(ToolCall("generate_image", """{"prompt":" a red bike ","shape":"landscape"}"""))
        )
        // Unknown shapes and lengths fall back to safe defaults.
        assertEquals(
            MediaRequest.Image("x", "square"),
            MediaRequest.fromToolCall(ToolCall("generate_image", """{"prompt":"x","shape":"round"}"""))
        )
        assertEquals(
            MediaRequest.Video("waves", 8, "landscape"),
            MediaRequest.fromToolCall(ToolCall("generate_video", """{"prompt":"waves","seconds":"8","shape":"landscape"}"""))
        )
        assertEquals(
            MediaRequest.Video("waves", 4, "portrait"),
            MediaRequest.fromToolCall(ToolCall("generate_video", """{"prompt":"waves","seconds":"60","shape":"square"}"""))
        )
        assertNull(MediaRequest.fromToolCall(ToolCall("generate_image", """{"prompt":"  "}""")))
        assertNull(MediaRequest.fromToolCall(ToolCall("generate_image", """{"prompt":"${"x".repeat(4001)}"}""")))
        assertNull(MediaRequest.fromToolCall(ToolCall("propose_save_memory", """{"prompt":"x"}""")))
        assertNull(MediaRequest.fromToolCall(ToolCall("generate_image", "not json")))
    }

    private fun engineWith(call: ToolCall, text: String = "", allowMedia: Boolean = true, allowProposals: Boolean = true): HybridAiState.Completed {
        val provider = object : AiProvider {
            override val providerId = "fake"
            override val capabilities = AiProviderCapabilities(streaming = true, supportsPersonalContext = true, supportsOffline = false)
            override fun generateStream(request: AiProviderRequest): Flow<AiProviderEvent> =
                flowOf(AiProviderEvent.Completed(text, 5, toolCall = call))
        }
        return runBlocking {
            HybridAiEngine(app, cloudEngine = provider, onlineStatusOverride = { true }).processHybridRequest(
                prompt = "Draw a bike", mode = HybridRoutingMode.FORCE_CLOUD, identity = AiIdentityConfig(),
                activeMemories = emptyList(), projects = emptyList(), allowCloudRequest = true,
                allowProposals = allowProposals, allowMedia = allowMedia
            ).toList().last() as HybridAiState.Completed
        }
    }

    @Test fun `an image request becomes a media request, never a proposal card`() {
        val done = engineWith(ToolCall("generate_image", """{"prompt":"a red bike","shape":"square"}"""))
        assertEquals(MediaRequest.Image("a red bike", "square"), done.mediaRequest)
        assertNull(done.proposedAction)
        assertEquals("Creating that image now.", done.fullText)
        assertTrue(done.reasoningRationale.contains("goes to OpenAI"))

        val video = engineWith(ToolCall("generate_video", """{"prompt":"waves","seconds":"4","shape":"portrait"}"""), text = "Here's my idea.")
        assertEquals(MediaRequest.Video("waves", 4, "portrait"), video.mediaRequest)
        assertEquals("Here's my idea.", video.fullText)
        assertTrue(video.reasoningRationale.contains("unless you confirm"))
    }

    @Test fun `with the switch off, a creation tool call is ignored`() {
        val done = engineWith(ToolCall("generate_image", """{"prompt":"a red bike","shape":"square"}"""), text = "Sure.", allowMedia = false)
        assertNull(done.mediaRequest)
        assertNull(done.proposedAction)
    }

    @Test fun `the app asks the relay for the creation tools only with the switch on`() = runBlocking<Unit> {
        val server = MockWebServer()
        server.start()
        try {
            val stream = MockResponse().setHeader("content-type", "text/event-stream")
                .setBody("""data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"ok"}}""" + "\n\n" + """data: {"type":"message_stop"}""" + "\n\n")
            server.enqueue(stream)
            server.enqueue(stream)
            val provider = ClaudeRelayProvider(server.url("/").toString(), tokens)
            val request = AiProviderRequest(prompt = "Draw a cat", identity = AiIdentityConfig())
            provider.generateStream(request.copy(allowMedia = true)).toList()
            provider.generateStream(request).toList()
            val on = JSONObject(server.takeRequest().body.readUtf8())
            val off = JSONObject(server.takeRequest().body.readUtf8())
            assertEquals(true, on.getBoolean("media"))
            assertTrue(on.getString("system").contains("generate_image"))
            assertFalse(off.has("media"))
            assertTrue(off.getString("system").contains("can't create images or videos"))
        } finally {
            server.shutdown()
        }
    }

    @Test fun `images and videos are fetched from the relay with the user's ticket`() = runBlocking<Unit> {
        val server = MockWebServer()
        server.start()
        try {
            val media = RelayMedia(server.url("/").toString(), tokens)
            server.enqueue(MockResponse().setBody("""{"image":"AQID","mediaType":"image/jpeg"}"""))
            assertArrayEquals(byteArrayOf(1, 2, 3), media.generateImage("a cat", "square").getOrThrow())
            val imageCall = server.takeRequest()
            assertEquals("/v1/image", imageCall.path)
            assertEquals("Bearer token", imageCall.getHeader("Authorization"))
            assertEquals("""{"prompt":"a cat","shape":"square"}""", imageCall.body.readUtf8())

            server.enqueue(MockResponse().setBody("""{"id":"video_1","ticket":"t/1","status":"queued","progress":0}"""))
            val job = media.startVideo("waves", 8, "landscape").getOrThrow()
            assertEquals(RelayMedia.VideoJob("video_1", "t/1"), job)
            assertEquals("""{"prompt":"waves","seconds":"8","shape":"landscape"}""", server.takeRequest().body.readUtf8())

            server.enqueue(MockResponse().setBody("""{"status":"in_progress","progress":40}"""))
            val status = media.videoStatus(job).getOrThrow()
            assertEquals(40, status.progress)
            assertFalse(status.done)
            assertEquals("/v1/video/status?id=video_1&ticket=t%2F1", server.takeRequest().path)

            server.enqueue(MockResponse().setBody(Buffer().write(byteArrayOf(0, 0, 0, 24))))
            val target = File(app.cacheDir, "v.mp4.part")
            media.downloadVideo(job, target).getOrThrow()
            assertEquals(4L, target.length())
            assertEquals("/v1/video/content?id=video_1&ticket=t%2F1", server.takeRequest().path)
            target.delete()

            // The relay's reason is passed on as it is.
            server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":{"type":"media_not_configured","message":"The relay has no OpenAI key yet."}}"""))
            assertEquals("The relay has no OpenAI key yet.", media.generateImage("x", "square").exceptionOrNull()?.message)
        } finally {
            server.shutdown()
        }
    }
}
