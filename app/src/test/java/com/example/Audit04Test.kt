package com.example

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.backup.BackupCrypto
import com.example.backup.BackupManager
import com.example.data.local.AppDatabase
import com.example.data.model.ProjectEntity
import com.example.data.model.TodoEntity
import com.example.data.repository.DefaultChatRepository
import com.example.data.repository.DefaultProjectRepository
import com.example.viewmodel.AuraViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Fixes from audit 04. Each test names the finding it guards. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Audit04Test {
    private val app: Application = ApplicationProvider.getApplicationContext()

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() {
        Dispatchers.resetMain()
        AppDatabase.closeDatabase()
    }

    private fun pendingSaves() = app.cacheDir.listFiles { f -> f.name.startsWith("aura_backup_ready") }!!.size

    @Test fun `A4-01 a save that fails leaves the file it would have replaced untouched`() = runBlocking<Unit> {
        AppDatabase.getInstance(app).todoDao().insert(TodoEntity(title = "Pay rent"))
        val manager = BackupManager(app)
        val target = File(app.filesDir, "older-backup.aura")
        target.writeText("my older backup")
        val uri = Uri.fromFile(target)

        // Fails while the backup is being made (the passphrase is too short).
        try {
            manager.writeBackup(uri, "short".toCharArray())
            fail("expected the save to fail")
        } catch (e: BackupCrypto.BackupException) {
            assertTrue(e.message!!.contains("at least 8"))
        }
        assertEquals("my older backup", target.readText())
        assertEquals(0, pendingSaves())

        // A save that works replaces it with a backup that opens.
        val pass = "correct horse".toCharArray()
        manager.writeBackup(uri, pass.copyOf())
        val (summary, staged) = manager.inspect(target.readBytes(), pass.copyOf())
        assertEquals(1, summary.todos)
        staged.discard()
        assertEquals(0, pendingSaves())
        target.delete()
    }

    @Test fun `A4-02 opening another chat leaves the project at once`() = runBlocking<Unit> {
        val db = AppDatabase.getInstance(app)
        val chats = DefaultChatRepository(db.chatMessageDao(), db.chatSessionDao())
        val projects = DefaultProjectRepository(db.projectDao())
        val viewModel = AuraViewModel(app, chats, projectRepository = projects)
        val projectId = projects.insertProject(ProjectEntity(title = "Garden", description = "", category = "General"))
        val project = projects.getProjectById(projectId)!!
        val general = chats.getSessionById(chats.createNewSession("General"))!!

        viewModel.setActiveProject(project)
        withTimeout(5_000) { viewModel.activeSession.first { it?.associatedProjectId == projectId } }
        assertEquals(projectId, viewModel.activeProject.value?.id)

        viewModel.selectSession(general)
        // Checked straight after the call, with no waiting: the project must already be gone.
        assertEquals(null, viewModel.activeProject.value)

        // Opening the project's own chat brings the project back.
        viewModel.selectSession(chats.getSessionForProject(projectId)!!)
        val back = withTimeout(5_000) { viewModel.activeProject.first { it != null } }
        assertEquals(projectId, back!!.id)
    }

    @Test fun `the routing mode is remembered and a message that can't be sent is refused, not lost`() = runBlocking<Unit> {
        val first = AuraViewModel(app)
        assertEquals(com.example.ai.HybridRoutingMode.AUTO_HYBRID, first.routingMode.value)
        first.setRoutingMode(com.example.ai.HybridRoutingMode.FORCE_LOCAL)
        assertEquals(com.example.ai.HybridRoutingMode.FORCE_LOCAL, AuraViewModel(app).routingMode.value)
        first.setRoutingMode(com.example.ai.HybridRoutingMode.AUTO_HYBRID)

        assertEquals(false, first.sendMessage("   "))
        assertEquals(null, first.generatingSessionId.value)
    }

    @Test fun `A4-04 the helper screens from Compose's tools are closed to other apps`() {
        val pm = app.packageManager
        val info = pm.getPackageInfo(app.packageName, android.content.pm.PackageManager.GET_ACTIVITIES)
        val byName = info.activities!!.associateBy { it.name }
        for (name in listOf("androidx.compose.ui.tooling.PreviewActivity", "androidx.activity.ComponentActivity")) {
            val activity = byName[name]
            if (activity != null) assertEquals(name, false, activity.exported)
        }
        // Aura's own screen still opens from the launcher.
        assertEquals(true, byName[MainActivity::class.java.name]!!.exported)
        // (Context is used so the import of the app's package manager stays explicit.)
        assertTrue(app is Context)
    }
}
