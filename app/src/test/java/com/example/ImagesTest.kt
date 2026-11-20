package com.example

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.media.ExifInterface
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
import com.example.backup.BackupManager
import com.example.data.local.AppDatabase
import com.example.data.model.AiIdentityConfig
import com.example.data.model.AiModelUsed
import com.example.data.model.Attachments
import com.example.data.model.ChatImages
import com.example.data.model.ChatMessageEntity
import com.example.data.model.Sender
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File

/** Images attached to chat messages: prepared on the phone, sent only to Claude, kept in backups. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImagesTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    @After fun close() {
        AppDatabase.closeDatabase()
        ChatImages.dir(app).deleteRecursively()
    }

    /** A JPEG photo of [w] x [h] with a GPS location and the given orientation in its metadata. */
    private fun photo(w: Int, h: Int, orientation: Int = ExifInterface.ORIENTATION_NORMAL): ByteArray {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(Color.rgb(30, 120, 200))
        val file = File.createTempFile("photo", ".jpg", app.cacheDir)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        ExifInterface(file.path).apply {
            setAttribute(ExifInterface.TAG_GPS_LATITUDE, "51/1,30/1,0/1")
            setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
            setAttribute(ExifInterface.TAG_GPS_LONGITUDE, "0/1,7/1,0/1")
            setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, "W")
            setAttribute(ExifInterface.TAG_MAKE, "TestCam")
            setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
            saveAttributes()
        }
        return file.readBytes().also { file.delete() }
    }

    @Test fun `sizes stay within what Claude reads at full detail and never grow`() {
        val (w, h) = ChatImages.targetSize(4000, 3000)
        assertTrue(w <= ChatImages.MAX_LONG_EDGE && h <= ChatImages.MAX_LONG_EDGE)
        assertTrue(w.toLong() * h <= ChatImages.MAX_PIXELS + 3000)
        assertEquals(800 to 600, ChatImages.targetSize(800, 600))
        val (tw, th) = ChatImages.targetSize(1000, 20000)
        assertEquals(ChatImages.MAX_LONG_EDGE, th)
        assertTrue(tw >= 1)
    }

    @Test fun `a photo is shrunk, turned upright and loses its location and other metadata`() {
        val prepared = ChatImages.prepare(photo(3000, 2000, ExifInterface.ORIENTATION_ROTATE_90))
        // Turned upright: the long side is now the height.
        assertTrue("${prepared.width}x${prepared.height}", prepared.height > prepared.width)
        assertTrue(prepared.height <= ChatImages.MAX_LONG_EDGE)
        assertTrue(prepared.width.toLong() * prepared.height <= ChatImages.MAX_PIXELS + 3000)
        // A real JPEG, with no location, camera or orientation left in it.
        assertEquals(0xFF.toByte(), prepared.jpeg[0]); assertEquals(0xD8.toByte(), prepared.jpeg[1])
        val file = File.createTempFile("out", ".jpg", app.cacheDir).apply { writeBytes(prepared.jpeg) }
        val exif = ExifInterface(file.path)
        assertNull(exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
        assertNull(exif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE))
        assertNull(exif.getAttribute(ExifInterface.TAG_MAKE))
        file.delete()
    }

    @Test fun `files that aren't images are refused with a reason`() {
        try {
            ChatImages.prepare("%PDF-1.7 not an image".toByteArray())
            fail("expected a refusal")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("isn't an image"))
        }
    }

    @Test fun `images no message uses are deleted, others kept`() {
        val kept = ChatImages.save(app, ChatImages.prepare(photo(100, 80)))
        val unused = ChatImages.save(app, ChatImages.prepare(photo(100, 80)))
        assertTrue(ChatImages.isValidName(kept))
        assertFalse(ChatImages.isValidName("../../databases/aura_ai_database"))
        assertNull(ChatImages.file(app, "../x.jpg"))
        assertEquals(1, ChatImages.deleteUnused(app, setOf(kept)))
        assertNotNull(ChatImages.file(app, kept))
        assertNull(ChatImages.file(app, unused))
    }

    private class Recorder : AiProvider {
        var request: AiProviderRequest? = null
        var reply: List<AiProviderEvent> = listOf(AiProviderEvent.Completed("A blue square.", 5))
        override val providerId = "fake"
        override val capabilities = AiProviderCapabilities(streaming = true, supportsPersonalContext = true, supportsOffline = false)
        override fun generateStream(request: AiProviderRequest): Flow<AiProviderEvent> {
            this.request = request
            return flowOf(*reply.toTypedArray())
        }
    }

    private fun ask(
        cloud: Recorder, mode: HybridRoutingMode, prompt: String = "What is this?",
        cloudOn: Boolean = true, online: Boolean = true
    ) = runBlocking {
        HybridAiEngine(app, cloudEngine = cloud, onlineStatusOverride = { online }).processHybridRequest(
            prompt = prompt, mode = mode, identity = AiIdentityConfig(), activeMemories = emptyList(), projects = emptyList(),
            allowCloudRequest = cloudOn, images = listOf(byteArrayOf(1, 2, 3))
        ).toList().last() as HybridAiState.Completed
    }

    @Test fun `an image goes only to Claude, and nothing is sent when it can't`() {
        for ((label, result) in listOf(
            "cloud chat off" to ask(Recorder().also { r -> assertNull(r.request) }, HybridRoutingMode.AUTO_HYBRID, cloudOn = false),
            "local mode" to ask(Recorder(), HybridRoutingMode.FORCE_LOCAL),
            "offline" to ask(Recorder(), HybridRoutingMode.AUTO_HYBRID, online = false)
        )) {
            assertEquals(label, AiModelUsed.NONE, result.modelUsed)
            assertTrue(label, result.fullText.startsWith("Nothing was sent") || result.fullText.contains("Nothing was sent"))
        }
        val off = Recorder()
        ask(off, HybridRoutingMode.FORCE_CLOUD, cloudOn = false)
        assertNull("nothing reached Claude", off.request)

        // In Auto mode, a typed command with an image still goes to Claude (the phone can't see it).
        val auto = Recorder()
        val answered = ask(auto, HybridRoutingMode.AUTO_HYBRID, prompt = "remember that this is my bike")
        assertEquals(AiModelUsed.CLOUD, answered.modelUsed)
        assertArrayEquals(byteArrayOf(1, 2, 3), auto.request!!.images.single())
        assertTrue(answered.reasoningRationale.contains("the image you attached"))

        // If Claude fails, the phone doesn't answer as if there were no image.
        val failing = Recorder().apply { reply = listOf(AiProviderEvent.Error("overloaded")) }
        val failed = ask(failing, HybridRoutingMode.AUTO_HYBRID)
        assertEquals(AiModelUsed.NONE, failed.modelUsed)
        assertTrue(failed.fullText.contains("Claude couldn't answer"))
    }

    @Test fun `the image goes first in the newest message only, and isn't sent again later`() {
        val request = AiProviderRequest(
            prompt = "What is this?", identity = AiIdentityConfig(), images = listOf(byteArrayOf(1, 2, 3)),
            history = CloudHistory.fromMessages(
                listOf(
                    ChatMessageEntity(sender = Sender.USER, text = "", imageName = "img_00000000-0000-0000-0000-000000000000.jpg", timestamp = 1),
                    ChatMessageEntity(sender = Sender.AI, text = "A cat.", modelUsed = AiModelUsed.CLOUD, timestamp = 2)
                )
            )
        )
        val messages = ClaudeRelayProvider.messagesJson(request)
        assertEquals(CloudHistory.IMAGE_NOT_RESENT, messages.getJSONObject(0).getString("content"))
        assertEquals("A cat.", messages.getJSONObject(1).getString("content"))
        val blocks = messages.getJSONObject(2).getJSONArray("content")
        assertEquals("image", blocks.getJSONObject(0).getString("type"))
        val source = blocks.getJSONObject(0).getJSONObject("source")
        assertEquals("image/jpeg", source.getString("media_type"))
        assertEquals("AQID", source.getString("data"))
        assertEquals(JSONObject().put("type", "text").put("text", "What is this?").toString(), blocks.getJSONObject(1).toString())

        // An image on its own: no empty text block.
        val alone = ClaudeRelayProvider.messagesJson(request.copy(prompt = "", history = emptyList()))
        assertEquals(1, alone.getJSONObject(0).getJSONArray("content").length())
    }

    @Test fun `several images go in the order picked, are stored together, and are counted later`() {
        val request = AiProviderRequest(
            prompt = "Which one?", identity = AiIdentityConfig(),
            images = listOf(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6))
        )
        val blocks = ClaudeRelayProvider.messagesJson(request).getJSONObject(0).getJSONArray("content")
        assertEquals(listOf("image", "image", "text"), (0 until blocks.length()).map { blocks.getJSONObject(it).getString("type") })
        assertEquals("AQID", blocks.getJSONObject(0).getJSONObject("source").getString("data"))
        assertEquals("BAUG", blocks.getJSONObject(1).getJSONObject("source").getString("data"))

        val names = listOf(
            "img_00000000-0000-0000-0000-000000000001.jpg",
            "img_00000000-0000-0000-0000-000000000002.jpg"
        )
        val stored = Attachments.join(names)
        assertEquals(names, Attachments.names(stored))
        assertEquals(null, Attachments.join(emptyList()))
        // Damaged or unknown entries are skipped, never turned into paths.
        assertEquals(names.take(1), Attachments.names("${names[0]},../../secret,"))

        val history = CloudHistory.fromMessages(
            listOf(
                ChatMessageEntity(sender = Sender.USER, text = "Compare", imageName = stored, timestamp = 1),
                ChatMessageEntity(sender = Sender.AI, text = "The left one.", modelUsed = AiModelUsed.CLOUD, timestamp = 2)
            )
        )
        assertEquals("Compare\n\n${CloudHistory.notResent(names)}", history.first().text)
        assertTrue(history.first().text.contains("2 images"))

        val prompt = com.example.ai.AuraPrompt.system(AiIdentityConfig(), imagesAttached = 2)
        assertTrue(prompt.contains("2 images, in the order they picked them"))
    }

    @Test fun `the details say how many images went`() {
        val recorder = Recorder()
        val result = runBlocking {
            HybridAiEngine(app, cloudEngine = recorder, onlineStatusOverride = { true }).processHybridRequest(
                prompt = "Which?", mode = HybridRoutingMode.AUTO_HYBRID, identity = AiIdentityConfig(), activeMemories = emptyList(),
                projects = emptyList(), allowCloudRequest = true, images = listOf(byteArrayOf(1), byteArrayOf(2), byteArrayOf(3))
            ).toList().last() as HybridAiState.Completed
        }
        assertEquals(3, recorder.request!!.images.size)
        assertTrue(result.reasoningRationale, result.reasoningRationale.contains("the 3 images you attached"))
    }

    @Test fun `backups keep images and restoring puts them back`() = runBlocking<Unit> {
        val db = AppDatabase.getInstance(app)
        val name = ChatImages.save(app, ChatImages.prepare(photo(120, 90)))
        val original = ChatImages.file(app, name)!!.readBytes()
        db.chatMessageDao().insertMessage(ChatMessageEntity(sender = Sender.USER, text = "my bike", imageName = name))
        val manager = BackupManager(app)
        val pass = "correct horse".toCharArray()
        val backup = ByteArrayOutputStream().also { manager.writeBackup(it, pass.copyOf()) }.toByteArray()

        // The image is lost after the backup was made...
        ChatImages.dir(app).deleteRecursively()
        assertNull(ChatImages.file(app, name))

        // ...and comes back with the restore.
        val (_, staged) = manager.inspect(backup, pass.copyOf())
        manager.restore(staged)
        assertArrayEquals(original, ChatImages.file(app, name)!!.readBytes())
        val restored = AppDatabase.getInstance(app).chatMessageDao().imageNamesInUse()
        assertEquals(listOf(name), restored)
        // Nothing unlocked is left in the cache.
        assertTrue(app.cacheDir.listFiles { f -> f.name.startsWith("aura_restore_check") }!!.isEmpty())
    }
}
