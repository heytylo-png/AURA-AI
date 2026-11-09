package com.example

import com.example.auth.RelayTokenSource
import com.example.voice.RelaySpeech
import com.example.voice.SpeechText
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The ElevenLabs voice goes through the relay; every failure must come back as a readable reason. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RelaySpeechTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: MockWebServer

    private object Tokens : RelayTokenSource {
        override fun accessId() = "uid-1"
        override suspend fun idToken(forceRefresh: Boolean) = "token"
    }

    @Before fun start() { server = MockWebServer(); server.start() }
    @After fun stop() { server.shutdown() }

    private fun speech(url: String = server.url("/").toString()) = RelaySpeech(url, Tokens, tmp.root)

    @Test fun successSavesAudioAndSendsTextWithToken() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "audio/mpeg").setBody(Buffer().write(byteArrayOf(1, 2, 3))))
        val file = speech().fetch("Hello there.").getOrThrow()
        assertEquals(3L, file.length())
        val req = server.takeRequest()
        assertEquals("/v1/speak", req.path)
        assertEquals("Bearer token", req.getHeader("Authorization"))
        assertEquals("Hello there.", JSONObject(req.body.readUtf8()).getString("text"))
    }

    @Test fun outOfCreditsGivesReadableReason() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(402).setBody("""{"error":{"type":"tts_upstream_error","message":"quota exceeded"}}"""))
        val result = speech().fetch("Hi.")
        assertFalse(result.isSuccess)
        assertEquals("quota exceeded", result.exceptionOrNull()?.message)
    }

    @Test fun notConfiguredAndBareErrorsAreReadable() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":{"type":"tts_not_configured","message":"Speech isn't set up on the relay."}}"""))
        assertEquals("Speech isn't set up on the relay.", speech().fetch("Hi.").exceptionOrNull()?.message)
        server.enqueue(MockResponse().setResponseCode(500).setBody("oops"))
        assertEquals("HTTP 500", speech().fetch("Hi.").exceptionOrNull()?.message)
    }

    @Test fun missingRelayUrlFailsWithoutNetwork() = runBlocking {
        val result = speech(url = "").fetch("Hi.")
        assertTrue(result.exceptionOrNull()!!.message!!.contains("relay"))
        assertEquals(0, server.requestCount)
    }

    @Test fun prepareStripsMarkdownAndCapsAtSentence() {
        assertEquals("Bold and code here.", SpeechText.prepare("**Bold** and `code`\n\nhere."))
        val long = "This is one sentence. ".repeat(100)
        val out = SpeechText.prepare(long, max = 200)
        assertTrue(out.length <= 200)
        assertTrue(out.endsWith("."))
        val words = "word ".repeat(100)
        val cut = SpeechText.prepare(words, max = 50)
        assertTrue(cut.length <= 51 && cut.endsWith("…"))
    }
}
