package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.actions.ActionExecutor
import com.example.actions.ActionResult
import com.example.ai.LocalModelEngine
import com.example.data.local.AppDatabase
import com.example.data.model.ActionStatus
import com.example.data.model.ActionType
import com.example.data.model.AiIdentityConfig
import com.example.data.model.PersonalityArchetype
import com.example.data.model.ProjectEntity
import com.example.data.model.TaskActionEntity
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Approved actions must only be recorded as executed when their operation succeeded
 * (project rule: no false success states).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ActionExecutorTest {

    private lateinit var db: AppDatabase
    private lateinit var executor: ActionExecutor

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        executor = ActionExecutor(db, clock = { 1_000L })
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun insert(type: ActionType, payload: String, projectId: Long? = null): Long =
        db.taskActionDao().insertAction(
            TaskActionEntity(
                title = "t",
                description = "",
                actionType = type,
                reasoning = "r",
                payloadJson = payload,
                associatedProjectId = projectId
            )
        )

    private suspend fun statusOf(id: Long) = db.taskActionDao().getActionById(id)!!.status

    @Test
    fun `successful save memory is recorded as executed with its effect`() = runBlocking {
        val id = insert(ActionType.SAVE_MEMORY, """{"content":"Likes tea","category":"PREFERENCE","importance":4}""")

        assertEquals(ActionResult.Succeeded, executor.execute(id))

        val action = db.taskActionDao().getActionById(id)!!
        assertEquals(ActionStatus.APPROVED_EXECUTED, action.status)
        assertEquals(1_000L, action.executedAt)
        assertEquals(listOf("Likes tea"), db.memoryDao().getActiveMemories().map { it.content })
    }

    @Test
    fun `invalid payload is recorded as failed and writes nothing`() = runBlocking {
        val id = insert(ActionType.SAVE_MEMORY, "{not json")

        assertTrue(executor.execute(id) is ActionResult.Failed)

        assertEquals(ActionStatus.FAILED, statusOf(id))
        assertNull(db.taskActionDao().getActionById(id)!!.executedAt)
        assertEquals(0, db.memoryDao().getMemoriesCount().first())
    }

    @Test
    fun `unimplemented create todo is recorded as failed, never executed`() = runBlocking {
        val id = insert(ActionType.CREATE_TODO, """{"title":"Buy milk","projectId":null}""")

        val result = executor.execute(id)

        assertTrue(result is ActionResult.Failed)
        assertEquals(ActionStatus.FAILED, statusOf(id))
        assertFalse(ActionExecutor.isSupported(ActionType.CREATE_TODO))
    }

    @Test
    fun `milestone for a missing project fails instead of defaulting to project 1`() = runBlocking {
        val noProject = insert(ActionType.ADD_MILESTONE, """{"title":"Ship"}""")
        val deletedProject = insert(ActionType.ADD_MILESTONE, """{"title":"Ship","projectId":42}""")

        assertTrue(executor.execute(noProject) is ActionResult.Failed)
        assertTrue(executor.execute(deletedProject) is ActionResult.Failed)

        assertEquals(ActionStatus.FAILED, statusOf(noProject))
        assertEquals(ActionStatus.FAILED, statusOf(deletedProject))
    }

    @Test
    fun `milestone for an existing project succeeds`() = runBlocking {
        val projectId = db.projectDao().insertProject(ProjectEntity(title = "Aura", description = "d"))
        val id = insert(ActionType.ADD_MILESTONE, """{"title":"Ship","projectId":$projectId}""")

        assertEquals(ActionResult.Succeeded, executor.execute(id))

        assertEquals(listOf("Ship"), db.projectDao().getMilestonesForProjectList(projectId).map { it.title })
    }

    @Test
    fun `create project writes project and kickoff milestone together`() = runBlocking {
        val id = insert(ActionType.CREATE_PROJECT, """{"title":"Garden","description":"d","category":"Home"}""")

        assertEquals(ActionResult.Succeeded, executor.execute(id))

        val project = db.projectDao().getAllProjectsSnapshot().single()
        assertEquals("Garden", project.title)
        assertEquals(1, db.projectDao().getMilestonesForProjectList(project.id).size)
    }

    @Test
    fun `approving twice runs the action once`() = runBlocking {
        val id = insert(ActionType.SAVE_MEMORY, """{"content":"Once"}""")

        val results = listOf(async { executor.execute(id) }, async { executor.execute(id) }).awaitAll()

        assertEquals(1, results.count { it == ActionResult.Succeeded })
        assertEquals(1, results.count { it == ActionResult.NotPending })
        assertEquals(1, db.memoryDao().getMemoriesCount().first())
    }

    @Test
    fun `dismissed or completed actions cannot run or be re-dismissed`() = runBlocking {
        val dismissed = insert(ActionType.SAVE_MEMORY, """{"content":"No"}""")
        assertEquals(1, db.taskActionDao().dismissIfProposed(dismissed))
        assertEquals(ActionResult.NotPending, executor.execute(dismissed))
        assertEquals(ActionStatus.DISMISSED, statusOf(dismissed))

        val done = insert(ActionType.SAVE_MEMORY, """{"content":"Yes"}""")
        executor.execute(done)
        assertEquals(0, db.taskActionDao().dismissIfProposed(done))
        assertEquals(ActionStatus.APPROVED_EXECUTED, statusOf(done))
        assertEquals(1, db.memoryDao().getMemoriesCount().first())
    }

    @Test
    fun `actions interrupted mid-run are marked failed on recovery`() = runBlocking {
        val id = insert(ActionType.SAVE_MEMORY, """{"content":"x"}""")
        assertEquals(1, db.taskActionDao().claimForExecution(id))

        assertEquals(1, executor.recoverInterrupted())

        assertEquals(ActionStatus.FAILED, statusOf(id))
        assertEquals(0, db.memoryDao().getMemoriesCount().first())
    }

    @Test
    fun `keyword engine builds valid payloads from text with quotes and backslashes`() = runBlocking {
        val tricky = """my favorite quote is "stay hungry" \ and a "nested" one"""
        val result = LocalModelEngine().processQuery(
            prompt = "Remember that $tricky",
            identity = AiIdentityConfig(name = "Aura", archetype = PersonalityArchetype.ANALYTICAL),
            activeMemories = emptyList(),
            projects = emptyList()
        )
        assertNotNull(result.proposedAction)
        val proposed = result.proposedAction!!

        val payload = JSONObject(proposed.payloadJson)
        assertEquals(proposed.description, payload.getString("content"))
        assertTrue(payload.getString("content").contains("\"stay hungry\""))

        val id = db.taskActionDao().insertAction(proposed)
        assertEquals(ActionResult.Succeeded, executor.execute(id))
        assertEquals(proposed.description, db.memoryDao().getActiveMemories().single().content)
    }
}
