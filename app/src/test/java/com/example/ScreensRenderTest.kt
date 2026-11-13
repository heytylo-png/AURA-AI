package com.example

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.model.AiModelUsed
import com.example.data.model.ChatMessageEntity
import com.example.data.model.ChatSessionEntity
import com.example.data.model.MemoryCategory
import com.example.data.model.MemoryEntity
import com.example.data.model.MilestoneEntity
import com.example.data.model.ProjectEntity
import com.example.data.model.Sender
import com.example.data.model.TodoEntity
import com.example.ui.MainScreen
import com.example.ui.theme.AuraTheme
import com.example.viewmodel.AuraViewModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Draws every main screen with some realistic data, on a phone-sized display, the way the app
 * would. A screen that crashes while being laid out or drawn fails the test. Each screen is also
 * saved as a picture under app/build/reports/screenshots/ (CI keeps them with every run), so a
 * change can be looked at without installing it.
 *
 * It does not compare against saved pictures: it catches screens that break, not ones that
 * merely look different.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreensRenderTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @After fun close() = AppDatabase.closeDatabase()

    private val screens = listOf("dashboard", "chat", "projects", "tasks", "memory")

    private fun seed(app: Application) = runBlocking {
        val db = AppDatabase.getInstance(app)
        db.memoryDao().insertMemory(MemoryEntity(content = "Likes green tea", category = MemoryCategory.PREFERENCE))
        db.todoDao().insert(TodoEntity(title = "Pay rent", dueEpochDay = 20_500))
        db.todoDao().insert(TodoEntity(title = "Order seeds"))
        val project = db.projectDao().insertProject(ProjectEntity(title = "Garden", description = "Raised beds for spring", category = "General"))
        db.projectDao().insertMilestone(MilestoneEntity(projectId = project, title = "Buy soil", isCompleted = true))
        db.projectDao().insertMilestone(MilestoneEntity(projectId = project, title = "Build beds", isCompleted = false))
        val chat = db.chatSessionDao().insertSession(ChatSessionEntity(title = "Planting"))
        db.chatMessageDao().insertMessage(ChatMessageEntity(sender = Sender.USER, text = "What should I plant first?", sessionId = chat))
        db.chatMessageDao().insertMessage(
            ChatMessageEntity(
                sender = Sender.AI, text = "Start with **peas** and lettuce:\n\n- hardy\n- quick",
                modelUsed = AiModelUsed.CLOUD, sessionId = chat
            )
        )
    }

    private fun capture(name: String) {
        rule.waitForIdle()
        lateinit var picture: Bitmap
        rule.runOnUiThread {
            val view = rule.activity.window.decorView
            picture = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(picture))
        }
        val dir = File("build/reports/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { picture.compress(Bitmap.CompressFormat.PNG, 100, it) }

        // Something was really drawn: a blank or single-colour picture means the screen didn't render.
        val colours = hashSetOf<Int>()
        var y = 0
        while (y < picture.height) {
            var x = 0
            while (x < picture.width) { colours += picture.getPixel(x, y); x += 7 }
            y += 7
        }
        assertTrue("$name drew only ${colours.size} colours", colours.size > 20)
    }

    @Test fun everyMainScreenDraws() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        seed(app)
        val viewModel = AuraViewModel(app)
        rule.setContent { AuraTheme(darkTheme = true) { MainScreen(viewModel = viewModel) } }

        screens.forEachIndexed { tab, name ->
            rule.runOnUiThread { viewModel.setSelectedTab(tab) }
            rule.onNodeWithTag("bottom_nav_bar").assertExists()
            capture(name)
        }
        rule.runOnUiThread { viewModel.setShowSettings(true) }
        capture("settings")
    }
}
