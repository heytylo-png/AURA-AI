package com.example

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.ai.AiProvider
import com.example.ai.AiProviderCapabilities
import com.example.ai.AiProviderEvent
import com.example.ai.AiProviderRequest
import com.example.ai.ChatLinks
import com.example.ai.ChatTurn
import com.example.ai.ClaudeRelayProvider
import com.example.ai.HybridAiEngine
import com.example.ai.HybridAiState
import com.example.ai.HybridRoutingMode
import com.example.auth.RelayTokenSource
import com.example.data.model.AiIdentityConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Claude opening links from the chat: when the app asks for it, how a reply is read, what is shown. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LinkReadingTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    private val tokens = object : RelayTokenSource {
        override fun accessId(): String = "uid"
        override suspend fun idToken(forceRefresh: Boolean): String = "token"
    }

    private fun sse(vararg events: String) = MockResponse()
        .setHeader("content-type", "text/event-stream")
        .setBody(events.joinToString("") { "data: $it\n\n" })

    private fun text(index: Int, t: String) =
        """{"type":"content_block_delta","index":$index,"delta":{"type":"text_delta","text":${JSONObject.quote(t)}}}"""

    private val stop = """{"type":"message_stop"}"""

    private fun sentBody(request: AiProviderRequest): JSONObject = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(sse(text(0, "ok"), stop))
            ClaudeRelayProvider(server.url("/").toString(), tokens).generateStream(request).toList()
            JSONObject(server.takeRequest().body.readUtf8())
        } finally {
            server.shutdown()
        }
    }

    @Test fun `links are found in messages, and their site is shown short`() {
        assertTrue(ChatLinks.hasLink("read https://example.com/a?b=1 please"))
        assertTrue(ChatLinks.hasLink("HTTP://EXAMPLE.COM"))
        assertFalse(ChatLinks.hasLink("example.com without a scheme"))
        assertFalse(ChatLinks.hasLink("ftp://example.com/file"))
        assertEquals("example.com", ChatLinks.host("https://www.example.com/page"))
        assertEquals("news.example.org", ChatLinks.host("http://news.example.org"))
    }

    @Test fun `the link tool is asked for only with the switch on and a link in the chat`() {
        val base = AiProviderRequest(prompt = "What does this say? https://example.com/post", identity = AiIdentityConfig(), allowLinkReading = true)

        val on = sentBody(base)
        assertEquals(true, on.getBoolean("fetch"))
        assertTrue(on.getString("system").contains("web_fetch tool"))
        assertTrue(on.getString("system").contains("not instructions"))

        // A link in an earlier turn counts too.
        val earlier = sentBody(base.copy(prompt = "Summarise it", history = listOf(
            ChatTurn(ChatTurn.Role.USER, "Look at https://example.com/post"), ChatTurn(ChatTurn.Role.ASSISTANT, "Sure.")
        )))
        assertEquals(true, earlier.getBoolean("fetch"))

        val noLink = sentBody(base.copy(prompt = "Hello"))
        assertFalse(noLink.has("fetch"))
        assertTrue(noLink.getString("system").contains("or open links"))

        val switchOff = sentBody(base.copy(allowLinkReading = false))
        assertFalse(switchOff.has("fetch"))
    }

    @Test fun `a reply that opened a link is read into its text and the links opened`() = runBlocking<Unit> {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(sse(
                """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
                text(0, "Let me read it."),
                """{"type":"content_block_stop","index":0}""",
                """{"type":"content_block_start","index":1,"content_block":{"type":"server_tool_use","id":"s1","name":"web_fetch","input":{}}}""",
                """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"url\": \"https://exa"}}""",
                """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"mple.com/post\"}"}}""",
                """{"type":"content_block_stop","index":1}""",
                """{"type":"content_block_start","index":2,"content_block":{"type":"web_fetch_tool_result","tool_use_id":"s1","content":{}}}""",
                """{"type":"content_block_stop","index":2}""",
                """{"type":"content_block_start","index":3,"content_block":{"type":"text","text":""}}""",
                text(3, "It says hello."),
                """{"type":"content_block_stop","index":3}""",
                """{"type":"message_delta","delta":{"stop_reason":"end_turn"}}""",
                stop
            ))
            val request = AiProviderRequest(prompt = "https://example.com/post", identity = AiIdentityConfig(), allowLinkReading = true)
            val events = ClaudeRelayProvider(server.url("/").toString(), tokens).generateStream(request).toList()
            assertTrue(events.contains(AiProviderEvent.ReadingLink("https://example.com/post")))
            val done = events.last() as AiProviderEvent.Completed
            assertEquals("Let me read it.\n\nIt says hello.", done.fullText)
            assertEquals(listOf("https://example.com/post"), done.linksRead)
        } finally {
            server.shutdown()
        }
    }

    @Test fun `the details say which links Claude opened`() {
        val provider = object : AiProvider {
            override val providerId = "fake"
            override val capabilities = AiProviderCapabilities(streaming = true, supportsPersonalContext = true, supportsOffline = false)
            override fun generateStream(request: AiProviderRequest): Flow<AiProviderEvent> = flowOf(
                AiProviderEvent.ReadingLink("https://example.com/post"),
                AiProviderEvent.Completed("It says hello.", 5, linksRead = listOf("https://example.com/post"))
            )
        }
        val states = runBlocking {
            HybridAiEngine(app, cloudEngine = provider, onlineStatusOverride = { true }).processHybridRequest(
                prompt = "https://example.com/post", mode = HybridRoutingMode.FORCE_CLOUD, identity = AiIdentityConfig(),
                activeMemories = emptyList(), projects = emptyList(), allowCloudRequest = true, allowLinkReading = true
            ).toList()
        }
        assertTrue(states.contains(HybridAiState.ReadingLink("https://example.com/post")))
        val done = states.last() as HybridAiState.Completed
        assertTrue(done.reasoningRationale, done.reasoningRationale.contains("Claude opened a link: https://example.com/post."))
    }

    @Test fun `tapped links open in a browser view inside Aura, and only web links open`() {
        assertTrue(com.example.ui.components.InAppBrowser.open(app, "https://example.com/page"))
        val started = org.robolectric.Shadows.shadowOf(app).nextStartedActivity
        assertEquals(android.content.Intent.ACTION_VIEW, started.action)
        assertEquals("https://example.com/page", started.dataString)
        // The Custom Tabs marker is what makes the browser show it as a view on top of Aura.
        assertTrue(started.hasExtra("android.support.customtabs.extra.SESSION"))

        assertFalse(com.example.ui.components.InAppBrowser.open(app, "javascript:alert(1)"))
        assertFalse(com.example.ui.components.InAppBrowser.open(app, "file:///data/data/com.example/secret"))
        assertEquals(null, org.robolectric.Shadows.shadowOf(app).nextStartedActivity)
    }
}
