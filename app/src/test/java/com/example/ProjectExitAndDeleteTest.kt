package com.example

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.model.ProjectEntity
import com.example.data.repository.DefaultChatRepository
import com.example.data.repository.DefaultProjectRepository
import com.example.ui.screens.deleteProjectWarning
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Leaving a project leaves its chat; deleting a project says what goes with it. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ProjectExitAndDeleteTest {

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() {
        Dispatchers.resetMain()
        AppDatabase.closeDatabase()
    }

    @Test fun `leaving a project switches to a general chat`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val db = AppDatabase.getInstance(app)
        val chats = DefaultChatRepository(db.chatMessageDao(), db.chatSessionDao())
        val projects = DefaultProjectRepository(db.projectDao())
        val viewModel = AuraViewModel(app, chats, projectRepository = projects)
        val projectId = projects.insertProject(ProjectEntity(title = "Garden", description = "", category = "General"))
        val project = projects.getProjectById(projectId)!!

        viewModel.setActiveProject(project)
        val inProject = withTimeout(5_000) { viewModel.activeSession.first { it?.associatedProjectId == projectId } }
        assertNotNull(inProject)

        viewModel.setActiveProject(null)
        val after = withTimeout(5_000) { viewModel.activeSession.first { it != null && it.associatedProjectId == null } }
        assertEquals(null, after!!.associatedProjectId)
        assertEquals(null, viewModel.activeProject.value)
        // The project's chat is still there to come back to.
        assertNotNull(chats.getSessionForProject(projectId))
    }

    @Test fun `the delete warning lists what is removed`() {
        val full = deleteProjectWarning(milestones = 3, notes = 1)
        assertTrue(full, full.contains("3 milestones, 1 note, its chat with Aura"))
        assertTrue(full, full.contains("can't be undone"))
        assertTrue(full, full.contains("to-dos are kept"))
        val empty = deleteProjectWarning(milestones = 0, notes = 0)
        assertTrue(empty, empty.contains("deletes the project, its chat with Aura."))
    }
}
