package com.example

import com.example.ai.AiProviderEvent
import com.example.ai.AiProviderRequest
import com.example.ai.ClaudeRelayProvider
import com.example.auth.RelayTokenSource
import com.example.data.model.AiIdentityConfig
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Claude provider must stream text, and turn every failure into a readable error. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ClaudeRelayProviderTest {

    private lateinit var server: MockWebServer

    private class FakeTokens(var fail: Boolean = false) : RelayTokenSource {
        val requests = mutableListOf<Boolean>()
        override fun accessId(): String = "uid-1"
        override suspend fun idToken(forceRefresh: Boolean): String {
            requests += forceRefresh
            if (fail) throw IllegalStateException("Couldn't sign in to the Claude relay (test).")
            return if (forceRefresh) "fresh-token" else "token"
        }
    }

    private val request = AiProviderRequest(prompt = "Hello Claude", identity = AiIdentityConfig(name = "Aura"))

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun provider(tokens: RelayTokenSource = FakeTokens(), url: String = server.url("/").toString()) =
        ClaudeRelayProvider(url, tokens)

    private fun sse(vararg events: String) = MockResponse()
        .setHeader("content-type", "text/event-stream")
        .setBody(events.joinToString("") { "event: x\ndata: $it\n\n" })

    private fun delta(text: String) = JSONObject()
        .put("type", "content_block_delta")
        .put("delta", JSONObject().put("type", "text_delta").put("text", text))
        .toString()

    private fun errorBody(status: Int, type: String, message: String) = MockResponse()
        .setResponseCode(status)
        .setHeader("content-type", "application/json")
        .setBody(JSONObject().put("error", JSONObject().put("type", type).put("message", message)).toString())

    @Test
    fun `streams text and completes, sending the prompt and system text to the relay`() = runBlocking {
        server.enqueue(sse("""{"type":"message_start"}""", delta("Hel"), delta("lo!"), """{"type":"message_stop"}"""))

        val events = provider().generateStream(request).toList()

        assertEquals(AiProviderEvent.Content("Hel"), events[0])
        assertEquals(AiProviderEvent.Content("Hello!"), events[1])
        val done = events.last() as AiProviderEvent.Completed
        assertEquals("Hello!", done.fullText)

        val recorded = server.takeRequest()
        assertEquals("/v1/chat", recorded.path)
        assertEquals("Bearer token", recorded.getHeader("Authorization"))
        val body = JSONObject(recorded.body.readUtf8())
        assertEquals("Hello Claude", body.getJSONArray("messages").getJSONObject(0).getString("content"))
        assertEquals("user", body.getJSONArray("messages").getJSONObject(0).getString("role"))
        assertTrue(body.getString("system").startsWith("You are Aura"))
        assertTrue("the app must not choose the model", !body.has("model"))
    }

    @Test
    fun `not-allowed access ID is reported with how to fix it`() = runBlocking {
        server.enqueue(errorBody(403, "user_not_allowed", "This access ID is not allowed to use the relay yet: uid-1"))

        val event = provider().generateStream(request).toList().single() as AiProviderEvent.Error

        assertTrue(event.message.contains("HTTP 403"))
        assertTrue(event.message.contains("uid-1"))
        assertTrue(event.message.contains("AURA_ALLOWED_UIDS"))
    }

    @Test
    fun `expired token is refreshed once and the request retried`() = runBlocking {
        server.enqueue(errorBody(401, "token_expired", "Sign-in token has expired."))
        server.enqueue(sse(delta("ok"), """{"type":"message_stop"}"""))
        val tokens = FakeTokens()

        val events = provider(tokens).generateStream(request).toList()

        assertTrue(events.last() is AiProviderEvent.Completed)
        assertEquals(listOf(false, true), tokens.requests)
        server.takeRequest()
        assertEquals("Bearer fresh-token", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `stream that ends without message_stop is an error, not a completed answer`() = runBlocking {
        server.enqueue(sse(delta("partial")))

        val events = provider().generateStream(request).toList()

        assertEquals(AiProviderEvent.Content("partial"), events.first())
        assertTrue((events.last() as AiProviderEvent.Error).message.contains("ended before Claude finished"))
    }

    @Test
    fun `error event inside the stream is reported`() = runBlocking {
        server.enqueue(sse("""{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}"""))

        val event = provider().generateStream(request).toList().single() as AiProviderEvent.Error

        assertTrue(event.message.contains("Overloaded"))
    }

    @Test
    fun `missing relay address and failed sign-in are reported without any network call`() = runBlocking {
        val noUrl = provider(url = "").generateStream(request).toList().single() as AiProviderEvent.Error
        assertTrue(noUrl.message.contains("AURA_RELAY_URL"))

        val noSignIn = provider(FakeTokens(fail = true)).generateStream(request).toList().single() as AiProviderEvent.Error
        assertTrue(noSignIn.message.contains("Couldn't sign in"))

        assertEquals(0, server.requestCount)
    }

    @Test
    fun `unreachable relay is reported`() = runBlocking {
        val url = server.url("/").toString()
        server.shutdown()

        val event = ClaudeRelayProvider(url, FakeTokens()).generateStream(request).toList().single() as AiProviderEvent.Error

        assertTrue(event.message.contains("Couldn't reach the Claude relay"))
    }

    @Test
    fun `parses only the stream lines that matter`() {
        assertEquals(ClaudeRelayProvider.SseEvent.TextDelta("a"), ClaudeRelayProvider.parseSseLine("data: ${delta("a")}"))
        assertEquals(ClaudeRelayProvider.SseEvent.Stop, ClaudeRelayProvider.parseSseLine("""data: {"type":"message_stop"}"""))
        assertEquals(null, ClaudeRelayProvider.parseSseLine("event: content_block_delta"))
        assertEquals(null, ClaudeRelayProvider.parseSseLine("""data: {"type":"ping"}"""))
        assertEquals(null, ClaudeRelayProvider.parseSseLine("data: not json"))
    }

    @Test
    fun `earlier turns are sent before the new message`() = runBlocking {
        server.enqueue(sse(delta("ok"), """{"type":"message_stop"}"""))
        val withHistory = request.copy(
            history = listOf(
                com.example.ai.ChatTurn(com.example.ai.ChatTurn.Role.USER, "first"),
                com.example.ai.ChatTurn(com.example.ai.ChatTurn.Role.ASSISTANT, "reply")
            )
        )

        provider().generateStream(withHistory).toList()

        val messages = JSONObject(server.takeRequest().body.readUtf8()).getJSONArray("messages")
        assertEquals(3, messages.length())
        assertEquals("user", messages.getJSONObject(0).getString("role"))
        assertEquals("first", messages.getJSONObject(0).getString("content"))
        assertEquals("assistant", messages.getJSONObject(1).getString("role"))
        assertEquals("reply", messages.getJSONObject(1).getString("content"))
        assertEquals("user", messages.getJSONObject(2).getString("role"))
        assertEquals("Hello Claude", messages.getJSONObject(2).getString("content"))
    }
}
