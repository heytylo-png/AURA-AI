package com.example

import android.app.Application
import android.app.NotificationManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
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
import com.example.ai.ReferenceImage
import com.example.ai.RelayMedia
import com.example.ai.ToolCall
import com.example.auth.RelayTokenSource
import com.example.backup.BackupManager
import com.example.data.local.AppDatabase
import com.example.data.model.AiIdentityConfig
import com.example.data.model.AiModelUsed
import com.example.data.model.ChatImages
import com.example.data.model.MediaLibrary
import com.example.data.model.SavedMediaEntity
import com.example.data.model.TodoEntity
import com.example.reminders.ReminderPrefs
import com.example.reminders.Reminders
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File

/** The media library: saving, references for Claude, editing, stickers, pictures on reminders, backups. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LibraryTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    private val tokens = object : RelayTokenSource {
        override fun accessId(): String = "uid"
        override suspend fun idToken(forceRefresh: Boolean): String = "token"
    }

    @After fun cleanUp() {
        MediaLibrary.dir(app).deleteRecursively()
        ChatImages.dir(app).deleteRecursively()
    }

    private fun picture(w: Int, h: Int, format: Bitmap.CompressFormat = Bitmap.CompressFormat.JPEG): ByteArray {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        return ByteArrayOutputStream().also { bitmap.compress(format, 90, it) }.toByteArray()
    }

    @Test fun `a reference is found only when its whole name is mentioned`() {
        assertTrue(MediaLibrary.mentions("Is my dog too fat?", "my dog"))
        assertTrue(MediaLibrary.mentions("MY   DOG ate it", "My Dog"))
        assertFalse(MediaLibrary.mentions("my doghouse is red", "my dog"))
        assertFalse(MediaLibrary.mentions("dog", "my dog"))
        assertFalse(MediaLibrary.mentions("anything", "  "))
        assertTrue(MediaLibrary.mentions("Rex.", "rex"))

        val items = listOf(
            SavedMediaEntity(id = 1, fileName = "a", kind = MediaLibrary.KIND_IMAGE, label = "my dog", isReference = true),
            SavedMediaEntity(id = 2, fileName = "b", kind = MediaLibrary.KIND_IMAGE, label = "my car", isReference = false),
            SavedMediaEntity(id = 3, fileName = "c", kind = MediaLibrary.KIND_VIDEO, label = "my dog video", isReference = true),
            SavedMediaEntity(id = 4, fileName = "d", kind = MediaLibrary.KIND_STICKER, label = "dog", isReference = true)
        )
        // Not shared with Claude, and videos, are never references.
        assertEquals(listOf(1L, 4L), MediaLibrary.referencesIn("my dog and my car and my dog video", items).map { it.id })
        val many = (1..5).map { SavedMediaEntity(id = it.toLong(), fileName = "f$it", kind = MediaLibrary.KIND_IMAGE, label = "x$it", isReference = true) }
        assertEquals(MediaLibrary.MAX_REFERENCES_PER_MESSAGE, MediaLibrary.referencesIn("x1 x2 x3 x4 x5", many).size)
    }

    @Test fun `library files are separate copies with safe names, found alongside chat files`() {
        val chat = ChatImages.save(app, ChatImages.prepare(picture(40, 30)))
        val copy = MediaLibrary.copyIn(app, ChatImages.file(app, chat)!!)
        assertTrue(MediaLibrary.isValidName(copy))
        assertTrue(copy.endsWith(".jpg"))
        assertNotNull(MediaLibrary.find(app, copy))
        assertNotNull(MediaLibrary.find(app, chat))
        assertNull(MediaLibrary.find(app, "../../databases/aura_ai_database"))
        // Deleting the chat image leaves the library copy.
        ChatImages.file(app, chat)!!.delete()
        assertNotNull(MediaLibrary.file(app, copy))
        assertEquals("image/jpeg", MediaLibrary.mimeType(copy))
        assertEquals("video/mp4", MediaLibrary.mimeType("vid_x.mp4"))
        assertTrue(MediaLibrary.isVideoFile("vid_00000000-0000-0000-0000-000000000001.mp4"))
        assertFalse(MediaLibrary.isVideoFile(copy))

        // Unused library files go; ones a to-do or item uses stay.
        val other = MediaLibrary.writeIn(app, picture(10, 10, Bitmap.CompressFormat.PNG), "png")
        assertEquals(1, MediaLibrary.deleteUnused(app, setOf(copy)))
        assertNotNull(MediaLibrary.file(app, copy))
        assertNull(MediaLibrary.file(app, other))
    }

    @Test fun `library items and to-do pictures are stored, and survive the upgrade from version 8`() = runBlocking<Unit> {
        val db = AppDatabase.createInMemory(app)
        try {
            val id = db.savedMediaDao().insert(SavedMediaEntity(fileName = "lib_x.jpg", kind = MediaLibrary.KIND_IMAGE, label = "my dog", isReference = true))
            assertEquals("my dog", db.savedMediaDao().getReferences().single().label)
            db.savedMediaDao().update(db.savedMediaDao().getById(id)!!.copy(isReference = false))
            assertTrue(db.savedMediaDao().getReferences().isEmpty())
            val todo = db.todoDao().insert(TodoEntity(title = "Vet", imageName = "lib_y.jpg"))
            assertEquals(listOf("lib_y.jpg"), db.todoDao().imageNamesInUse())
            assertEquals(listOf("lib_x.jpg"), db.savedMediaDao().fileNamesInUse())
            assertTrue(todo > 0)
        } finally {
            db.close()
        }
    }

    private fun recorder(reply: AiProviderEvent = AiProviderEvent.Completed("Rex looks happy.", 5)) = object : AiProvider {
        var request: AiProviderRequest? = null
        override val providerId = "fake"
        override val capabilities = AiProviderCapabilities(streaming = true, supportsPersonalContext = true, supportsOffline = false)
        override fun generateStream(request: AiProviderRequest): Flow<AiProviderEvent> {
            this.request = request
            return flowOf(reply)
        }
    }

    @Test fun `references go to Claude first, with a note, and never decide where a message goes`() {
        val refs = listOf(ReferenceImage("my dog", byteArrayOf(7)))
        val cloud = recorder()
        val done = runBlocking {
            HybridAiEngine(app, cloudEngine = cloud, onlineStatusOverride = { true }).processHybridRequest(
                prompt = "Is my dog happy?", mode = HybridRoutingMode.FORCE_CLOUD, identity = AiIdentityConfig(),
                activeMemories = emptyList(), projects = emptyList(), allowCloudRequest = true,
                images = listOf(byteArrayOf(1)), references = refs
            ).toList().last() as HybridAiState.Completed
        }
        val sent = cloud.request!!
        assertEquals(listOf(7.toByte(), 1.toByte()), sent.images.map { it.single() })
        assertEquals(listOf("my dog"), sent.referenceLabels)
        assertTrue(sent.attachmentNote!!.contains("The first image is a picture the user saved in Aura and named: 1. “my dog”"))
        assertTrue(done.reasoningRationale, done.reasoningRationale.contains("1 saved picture from your library that your message mentions (“my dog”)"))
        assertTrue(done.reasoningRationale, done.reasoningRationale.contains("the image you attached"))

        // In Local mode a mentioned reference doesn't block the message or send anything.
        val local = recorder()
        val answered = runBlocking {
            HybridAiEngine(app, cloudEngine = local, onlineStatusOverride = { true }).processHybridRequest(
                prompt = "How is my dog?", mode = HybridRoutingMode.FORCE_LOCAL, identity = AiIdentityConfig(),
                activeMemories = emptyList(), projects = emptyList(), allowCloudRequest = true, references = refs
            ).toList().last() as HybridAiState.Completed
        }
        assertNull(local.request)
        assertEquals(AiModelUsed.LOCAL, answered.modelUsed)
    }

    @Test fun `PNG pictures are labelled as PNG for Claude`() {
        assertEquals("image/png", ClaudeRelayProvider.imageType(picture(4, 4, Bitmap.CompressFormat.PNG)))
        assertEquals("image/jpeg", ClaudeRelayProvider.imageType(picture(4, 4)))
        val json = ClaudeRelayProvider.messagesJson(AiProviderRequest(prompt = "x", identity = AiIdentityConfig(), images = listOf(picture(4, 4, Bitmap.CompressFormat.PNG))))
        assertEquals("image/png", json.getJSONObject(0).getJSONArray("content").getJSONObject(0).getJSONObject("source").getString("media_type"))
    }

    @Test fun `stickers keep their PNG, and a first frame is cut to the video's exact size`() {
        val name = ChatImages.savePng(app, picture(20, 20, Bitmap.CompressFormat.PNG))
        assertTrue(name.endsWith(".png"))
        assertTrue(ChatImages.isValidName(name))
        assertTrue(runCatching { ChatImages.savePng(app, picture(20, 20)) }.isFailure)

        val frame = ChatImages.fitExactly(picture(1000, 1000), 720, 1280)
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(frame, 0, frame.size, o)
        assertEquals(720 to 1280, o.outWidth to o.outHeight)
    }

    @Test fun `Claude's edit, sticker and first-frame options are read`() {
        assertEquals(
            MediaRequest.Image("watercolor", "square", fromAttachedImage = true, sticker = false),
            MediaRequest.fromToolCall(ToolCall("generate_image", """{"prompt":"watercolor","shape":"square","from_attached_image":true}"""))
        )
        assertEquals(
            MediaRequest.Image("a cactus", "square", sticker = true),
            MediaRequest.fromToolCall(ToolCall("generate_image", """{"prompt":"a cactus","shape":"square","sticker":true}"""))
        )
        assertEquals(
            MediaRequest.Video("it runs", 4, "portrait", fromAttachedImage = true),
            MediaRequest.fromToolCall(ToolCall("generate_video", """{"prompt":"it runs","seconds":"4","shape":"portrait","from_attached_image":true}"""))
        )
    }

    @Test fun `edits, stickers and first frames are sent to the relay`() = runBlocking<Unit> {
        val server = MockWebServer()
        server.start()
        try {
            val media = RelayMedia(server.url("/").toString(), tokens)
            val jpeg = picture(8, 8)
            server.enqueue(MockResponse().setBody("""{"image":"iVBORw==","mediaType":"image/png"}"""))
            val sticker = media.editImage("my dog", jpeg, sticker = true).getOrThrow()
            assertTrue(sticker.png)
            val edit = server.takeRequest()
            assertEquals("/v1/image/edit", edit.path)
            val body = JSONObject(edit.body.readUtf8())
            assertEquals("image/jpeg", body.getString("imageType"))
            assertEquals(true, body.getBoolean("sticker"))
            assertEquals(jpeg.size, android.util.Base64.decode(body.getString("image"), 0).size)

            server.enqueue(MockResponse().setBody("""{"id":"video_1","ticket":"t"}"""))
            media.startVideo("it runs", 4, "portrait", firstFrame = jpeg).getOrThrow()
            val video = JSONObject(server.takeRequest().body.readUtf8())
            assertEquals("image/jpeg", video.getString("imageType"))
            assertTrue(video.getString("image").isNotEmpty())
        } finally {
            server.shutdown()
        }
    }

    @Test fun `a custom reminder sound gets its own channel, and going back removes it`() {
        val prefs = ReminderPrefs(app)
        val sound = android.net.Uri.parse("content://media/external/audio/media/42")
        Reminders.useSound(app, sound)
        val custom = prefs.channelId
        assertTrue(custom != Reminders.CHANNEL_ID)
        val manager = app.getSystemService(NotificationManager::class.java)
        assertEquals(sound, manager.getNotificationChannel(custom).sound)
        Reminders.useSound(app, null)
        assertEquals(Reminders.CHANNEL_ID, prefs.channelId)
        assertNull(prefs.customSound)
        assertNull(manager.getNotificationChannel(custom))
    }

    @Test fun `backups carry library pictures but not videos, and not this phone's reminder sound`() = runBlocking<Unit> {
        val pictureName = MediaLibrary.writeIn(app, picture(10, 10, Bitmap.CompressFormat.PNG), "png")
        val video = File(MediaLibrary.dir(app), "lib_00000000-0000-0000-0000-000000000009.mp4").apply { writeBytes(byteArrayOf(1, 2)) }
        ReminderPrefs(app).customSound = "content://media/external/audio/media/42"
        ReminderPrefs(app).channelId = "todo_reminders_sound_1"
        val manager = BackupManager(app)
        val pass = "correct horse".toCharArray()
        val backup = manager.createBackup(pass.copyOf())
        assertFalse(manager.exportPrefs().contains("custom_sound"))

        MediaLibrary.file(app, pictureName)!!.delete()
        val (_, staged) = manager.inspect(backup, pass.copyOf())
        manager.restore(staged)
        assertNotNull("the library picture is back", MediaLibrary.file(app, pictureName))
        assertTrue("the library video was left alone", video.exists())
        assertEquals("content://media/external/audio/media/42", ReminderPrefs(app).customSound)
        ReminderPrefs(app).customSound = null
        ReminderPrefs(app).channelId = Reminders.CHANNEL_ID
    }
}
