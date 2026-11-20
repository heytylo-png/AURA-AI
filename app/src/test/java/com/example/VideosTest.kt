package com.example

import android.app.Application
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import androidx.test.core.app.ApplicationProvider
import com.example.ai.AiProvider
import com.example.ai.AiProviderCapabilities
import com.example.ai.AiProviderEvent
import com.example.ai.AiProviderRequest
import com.example.ai.ClaudeRelayProvider
import com.example.ai.CloudHistory
import com.example.ai.HybridAiEngine
import com.example.ai.HybridAiState
import com.example.ai.HybridRoutingMode
import com.example.data.model.AiIdentityConfig
import com.example.data.model.AiModelUsed
import com.example.data.model.Attachments
import com.example.data.model.ChatMessageEntity
import com.example.data.model.ChatVideos
import com.example.data.model.Sender
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaMetadataRetriever
import org.robolectric.shadows.util.DataSource
import java.io.File

/** Videos in chat: kept on the phone, played there, and shown to Claude as a few still frames. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VideosTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val name = "vid_00000000-0000-0000-0000-000000000001.mp4"
    private val picture = "img_00000000-0000-0000-0000-000000000002.jpg"

    @After fun cleanUp() {
        ChatVideos.dir(app).deleteRecursively()
    }

    @Test fun `frames are spread evenly, about one every two seconds, at most eight`() {
        assertEquals(listOf(0L), ChatVideos.frameTimes(0))
        assertEquals(listOf(500L), ChatVideos.frameTimes(1_000))
        assertEquals(listOf(1_000L, 3_000L, 5_000L), ChatVideos.frameTimes(6_000))
        val long = ChatVideos.frameTimes(10 * 60_000)
        assertEquals(ChatVideos.MAX_FRAMES, long.size)
        assertEquals(37_500L, long.first())
        assertTrue(long.zipWithNext().all { (a, b) -> b > a })
        assertEquals("0:07", ChatVideos.formatTime(7_400))
        assertEquals("1:05", ChatVideos.formatTime(65_000))
        assertEquals("1:02:03", ChatVideos.formatTime(3_723_000))
        // Frames stay small, and never grow past the video's own size.
        assertEquals(768 to 432, ChatVideos.frameSize(1920, 1080))
        assertEquals(432 to 768, ChatVideos.frameSize(1080, 1920))
        assertEquals(320 to 240, ChatVideos.frameSize(320, 240))
    }

    @Test fun `a video file is read into its length and still frames`() {
        val file = File(ChatVideos.dir(app), "$name.part").apply { writeBytes(byteArrayOf(0, 1, 2)) }
        val source = DataSource.toDataSource(file.path)
        ShadowMediaMetadataRetriever.addMetadata(source, MediaMetadataRetriever.METADATA_KEY_DURATION, "6000")
        for (t in listOf(1_000L, 3_000L, 5_000L)) {
            ShadowMediaMetadataRetriever.addFrame(source, t * 1000, Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888))
        }
        val prepared = ChatVideos.prepareFile(file, "mp4")
        assertEquals(6_000L, prepared.durationMs)
        assertEquals(listOf(1_000L, 3_000L, 5_000L), prepared.frames.map { it.timeMs })
        assertTrue(prepared.frames.all { it.jpeg.size > 2 && it.jpeg[0] == 0xFF.toByte() && it.jpeg[1] == 0xD8.toByte() })

        // Saving gives it a final name in Aura's storage; the temporary copy is gone.
        val saved = ChatVideos.save(app, prepared)
        assertTrue(ChatVideos.isValidName(saved))
        assertTrue(ChatVideos.file(app, saved)!!.isFile)
        assertFalse(file.exists())
    }

    @Test fun `a file with no pictures is refused with a reason`() {
        val file = File(ChatVideos.dir(app), "vid_x.mp4.part").apply { writeBytes(byteArrayOf(1)) }
        val error = runCatching { ChatVideos.prepareFile(file, "mp4") }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test fun `Claude gets the frames after the pictures, with when each was taken`() {
        val frames = listOf(ChatVideos.Frame(1_000, byteArrayOf(9)), ChatVideos.Frame(3_000, byteArrayOf(8)))
        val note = ChatVideos.noteForClaude(6_000, frames, picturesBefore = 1)
        assertTrue(note, note.contains("0:06 long"))
        assertTrue(note, note.contains("The last 2 images are still frames"))
        assertTrue(note, note.contains("taken at 0:01, 0:03"))
        assertTrue(note, note.contains("can't hear its sound"))
        assertTrue(ChatVideos.noteForClaude(1_000, frames.take(1), 0).contains("The image is a still frame"))

        val request = AiProviderRequest(
            prompt = "What happens?", identity = AiIdentityConfig(),
            images = listOf(byteArrayOf(1)) + frames.map { it.jpeg }, videoFrames = 2, attachmentNote = note
        )
        val blocks = ClaudeRelayProvider.messagesJson(request).getJSONObject(0).getJSONArray("content")
        assertEquals(listOf("image", "image", "image", "text"), (0 until blocks.length()).map { blocks.getJSONObject(it).getString("type") })
        assertEquals("$note\n\nWhat happens?", blocks.getJSONObject(3).getString("text"))
        // With no question, the note still says what the frames are.
        val alone = ClaudeRelayProvider.messagesJson(request.copy(prompt = "")).getJSONObject(0).getJSONArray("content")
        assertEquals(note, alone.getJSONObject(3).getString("text"))
    }

    @Test fun `the details say the frames went and the video stayed`() {
        val recorder = object : AiProvider {
            var request: AiProviderRequest? = null
            override val providerId = "fake"
            override val capabilities = AiProviderCapabilities(streaming = true, supportsPersonalContext = true, supportsOffline = false)
            override fun generateStream(request: AiProviderRequest): Flow<AiProviderEvent> {
                this.request = request
                return flowOf(AiProviderEvent.Completed("A dog runs.", 5))
            }
        }
        val done = runBlocking {
            HybridAiEngine(app, cloudEngine = recorder, onlineStatusOverride = { true }).processHybridRequest(
                prompt = "", mode = HybridRoutingMode.AUTO_HYBRID, identity = AiIdentityConfig(), activeMemories = emptyList(),
                projects = emptyList(), allowCloudRequest = true, images = listOf(byteArrayOf(1), byteArrayOf(2), byteArrayOf(3)),
                videoFrames = 3, attachmentNote = "[video]"
            ).toList().last() as HybridAiState.Completed
        }
        assertEquals(AiModelUsed.CLOUD, done.modelUsed)
        assertEquals("[video]", recorder.request!!.attachmentNote)
        assertTrue(done.reasoningRationale, done.reasoningRationale.contains("3 still frames from your video (no sound; the video itself stayed on this phone)"))
        assertFalse(done.reasoningRationale, done.reasoningRationale.contains("images you attached"))
    }

    @Test fun `messages keep videos with their pictures, and later turns say what was attached`() {
        val stored = Attachments.join(listOf(picture, name))
        assertEquals(listOf(picture, name), Attachments.names(stored))
        assertEquals("an image and a video", Attachments.describe(listOf(picture, name)))
        assertEquals("a video", Attachments.describe(listOf(name)))
        assertFalse(Attachments.names("vid_../../x.mp4").any())

        val history = CloudHistory.fromMessages(
            listOf(
                ChatMessageEntity(sender = Sender.USER, text = "Look", imageName = stored, timestamp = 1),
                ChatMessageEntity(sender = Sender.AI, text = "Nice.", modelUsed = AiModelUsed.CLOUD, timestamp = 2)
            )
        )
        assertTrue(history.first().text, history.first().text.contains("attached an image and a video to this message. They aren't sent again"))
    }

    @Test fun `videos no message uses are deleted, but not ones still waiting to be sent`() {
        val dir = ChatVideos.dir(app)
        val kept = File(dir, name).apply { writeBytes(byteArrayOf(1)) }
        val unused = File(dir, "vid_00000000-0000-0000-0000-000000000003.mp4").apply { writeBytes(byteArrayOf(1)) }
        val waiting = File(dir, "vid_00000000-0000-0000-0000-000000000004.mp4.part").apply { writeBytes(byteArrayOf(1)) }
        val abandoned = File(dir, "vid_00000000-0000-0000-0000-000000000005.mp4.part").apply { writeBytes(byteArrayOf(1)) }
        val now = System.currentTimeMillis()
        abandoned.setLastModified(now - 2 * 24 * 60 * 60 * 1000L)
        assertEquals(2, ChatVideos.deleteUnused(app, setOf(name), now))
        assertTrue(kept.exists())
        assertTrue(waiting.exists())
        assertFalse(unused.exists())
        assertFalse(abandoned.exists())
    }
}
