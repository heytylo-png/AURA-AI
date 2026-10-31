package com.example

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.ai.LocalModelEngine
import com.example.data.local.AppDatabase
import com.example.data.model.ActionType
import com.example.data.model.AiIdentityConfig
import com.example.data.model.AiModelUsed
import com.example.data.model.ChatMessageEntity
import com.example.data.model.PersonalityArchetype
import com.example.data.model.Sender
import com.example.data.repository.DefaultChatRepository
import com.example.viewmodel.ConversationViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Aura", appName)
    }

    @Test
    fun `localModelEngine parses memory intent and proposes action`() {
        val engine = LocalModelEngine()
        val identity = AiIdentityConfig(name = "Aura", archetype = PersonalityArchetype.ANALYTICAL)

        val result = engine.processQuery(
            prompt = "Remember that I prefer dark theme and concise answers",
            identity = identity,
            activeMemories = emptyList(),
            projects = emptyList()
        )

        assertNotNull(result.proposedAction)
        assertEquals(ActionType.SAVE_MEMORY, result.proposedAction?.actionType)
        assertTrue(result.responseText.contains("Memory registered") || result.responseText.contains("Aura"))
    }

    @Test
    fun `localModelEngine parses project creation intent`() {
        val engine = LocalModelEngine()
        val identity = AiIdentityConfig(name = "Aura", archetype = PersonalityArchetype.DIRECT_OPERATOR)

        val result = engine.processQuery(
            prompt = "Create project Mobile Redesign",
            identity = identity,
            activeMemories = emptyList(),
            projects = emptyList()
        )

        assertNotNull(result.proposedAction)
        assertEquals(ActionType.CREATE_PROJECT, result.proposedAction?.actionType)
        assertTrue(result.proposedAction?.title?.contains("Mobile Redesign") == true)
    }

    @Test
    fun `chatRepository persists and retrieves messages`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = AppDatabase.getInstance(context)
        val repository = DefaultChatRepository(db.chatMessageDao())

        repository.clearGlobalChat()

        val msgId = repository.insertMessage(
            ChatMessageEntity(
                sender = Sender.USER,
                text = "Hello Aura repository test",
                modelUsed = null
            )
        )
        assertTrue(msgId > 0)

        val globalMessages = repository.getGlobalMessages().first()
        assertTrue(globalMessages.any { it.text == "Hello Aura repository test" })
    }

    @Test
    fun `conversationViewModel persists messages and manages project scope`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val db = AppDatabase.getInstance(app)
        val repository = DefaultChatRepository(db.chatMessageDao())
        val viewModel = ConversationViewModel(app, repository)

        repository.clearGlobalChat()
        repository.clearProjectChat(42L)

        // Save global message
        viewModel.setProjectScope(null)
        val globalId = viewModel.persistMessageDirect(
            sender = Sender.USER,
            text = "Global conversation test"
        )
        assertTrue(globalId > 0)

        // Save project-scoped message
        viewModel.setProjectScope(42L)
        val projectId = viewModel.persistMessageDirect(
            sender = Sender.AI,
            text = "Project 42 response",
            modelUsed = AiModelUsed.LOCAL
        )
        assertTrue(projectId > 0)

        val projectMessages = repository.getMessagesForProject(42L).first()
        assertTrue(projectMessages.any { it.text == "Project 42 response" })
    }

    @Test
    fun `auraViewModel and conversationViewModel can be created by AndroidViewModelFactory`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val factory = androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.getInstance(app)
        val auraVm = factory.create(com.example.viewmodel.AuraViewModel::class.java)
        assertNotNull(auraVm)

        val convVm = factory.create(com.example.viewmodel.ConversationViewModel::class.java)
        assertNotNull(convVm)
    }

    @Test
    fun `userSettingsDao persists user name and primary interest`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = AppDatabase.getInstance(context)
        val dao = db.userSettingsDao()

        val settings = com.example.data.model.UserSettingsEntity(
            id = 1,
            userName = "HeyTyLo",
            primaryInterest = "Android Jetpack Compose"
        )
        dao.saveUserSettings(settings)

        val retrieved = dao.getUserSettingsSnapshot()
        assertNotNull(retrieved)
        assertEquals("HeyTyLo", retrieved?.userName)
        assertEquals("Android Jetpack Compose", retrieved?.primaryInterest)
    }

    @Test
    fun `cloudModelEngine prompt includes user name and primary interest system block`() {
        val engine = com.example.ai.CloudModelEngine()
        val identity = AiIdentityConfig(name = "Aura", archetype = PersonalityArchetype.ANALYTICAL)
        val settings = com.example.data.model.UserSettingsEntity(
            id = 1,
            userName = "HeyTyLo",
            primaryInterest = "Autonomous Edge Computing"
        )

        val prompt = engine.buildContextualPrompt(
            userMessage = "Help me design an architecture",
            identity = identity,
            activeMemories = emptyList(),
            currentProject = null,
            userSettings = settings
        )

        assertTrue(prompt.contains("User Name: HeyTyLo"))
        assertTrue(prompt.contains("Primary Interest: Autonomous Edge Computing"))
        assertTrue(prompt.contains("[USER PERSONALIZATION SETTINGS]"))
    }

    @Test
    fun `chatMessageEntity formats human readable timestamp`() {
        val now = System.currentTimeMillis()
        val message = ChatMessageEntity(
            sender = Sender.USER,
            text = "Timestamp test message",
            timestamp = now
        )

        val formatted = message.getFormattedTime()
        assertTrue(formatted.isNotBlank())
        assertTrue(formatted.contains(":") || formatted.contains("M"))
    }

    @Test
    fun `memoryRepository persists, searches, and filters active memories`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = AppDatabase.getInstance(context)
        val repository = com.example.data.repository.DefaultMemoryRepository(db.memoryDao())

        repository.clearAllMemories()

        val memory1 = com.example.data.model.MemoryEntity(
            content = "User prefers Kotlin Coroutines over RxJava",
            category = com.example.data.model.MemoryCategory.PREFERENCE,
            importance = 5,
            isActive = true
        )
        val memory2 = com.example.data.model.MemoryEntity(
            content = "User loves dark futuristic UI aesthetics",
            category = com.example.data.model.MemoryCategory.PREFERENCE,
            importance = 4,
            isActive = false
        )

        val id1 = repository.insertMemory(memory1)
        val id2 = repository.insertMemory(memory2)
        assertTrue(id1 > 0)
        assertTrue(id2 > 0)

        val active = repository.getActiveMemories()
        assertEquals(1, active.size)
        assertEquals("User prefers Kotlin Coroutines over RxJava", active.first().content)

        val searchResult = repository.searchMemories("Coroutines").first()
        assertEquals(1, searchResult.size)
        assertEquals(id1, searchResult.first().id)

        val totalCount = repository.getMemoriesCount().first()
        assertEquals(2, totalCount)

        repository.setMemoryActive(id2, true)
        val updatedActive = repository.getActiveMemories()
        assertEquals(2, updatedActive.size)
    }

    @Test
    fun `projectRepository handles ProjectWithDetails and cascades deletion to milestones and artifacts`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = AppDatabase.getInstance(context)
        val repository = com.example.data.repository.DefaultProjectRepository(db.projectDao())

        // 1. Create a project
        val project = com.example.data.model.ProjectEntity(
            title = "Aura Architecture Modernization",
            description = "Refactor storage layer to clean Room entities and DAOs",
            status = com.example.data.model.ProjectStatus.IN_PROGRESS
        )
        val projectId = repository.insertProject(project)
        assertTrue(projectId > 0)

        // 2. Add milestones linked by projectId
        val m1 = com.example.data.model.MilestoneEntity(
            projectId = projectId,
            title = "Room Converters and Schema v3",
            isCompleted = true
        )
        val m2 = com.example.data.model.MilestoneEntity(
            projectId = projectId,
            title = "Write Unit Tests for Cascades",
            isCompleted = false
        )
        repository.insertMilestone(m1)
        repository.insertMilestone(m2)

        // 3. Add artifact linked by projectId
        val artifact = com.example.data.model.ProjectArtifactEntity(
            projectId = projectId,
            title = "Schema Design Doc",
            content = "Entities with ForeignKeys and indices",
            type = "DOCUMENT"
        )
        val artifactId = repository.insertArtifact(artifact)
        assertTrue(artifactId > 0)

        // 4. Test multi-table composite relation query: ProjectWithDetails
        val details = repository.getProjectWithDetailsSnapshot(projectId)
        assertNotNull(details)
        assertEquals("Aura Architecture Modernization", details?.project?.title)
        assertEquals(2, details?.milestones?.size)
        assertEquals(1, details?.artifacts?.size)
        assertEquals("Schema Design Doc", details?.artifacts?.first()?.title)

        // 5. Test cascading delete: deleting the project must cascade delete child milestones and artifacts
        repository.deleteProjectById(projectId)

        val deletedDetails = repository.getProjectWithDetailsSnapshot(projectId)
        assertEquals(null, deletedDetails)

        val remainingMilestones = repository.getMilestonesForProjectList(projectId)
        assertTrue(remainingMilestones.isEmpty())

        val remainingArtifact = repository.getArtifactById(artifactId)
        assertEquals(null, remainingArtifact)
    }

    @Test
    fun `clearCurrentChat deletes all global messages from Room database`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val db = AppDatabase.getInstance(app)
        val chatRepo = DefaultChatRepository(db.chatMessageDao())
        val auraViewModel = com.example.viewmodel.AuraViewModel(app, chatRepo)

        chatRepo.clearGlobalChat()

        // Insert messages to Room
        chatRepo.insertMessage(
            ChatMessageEntity(sender = Sender.USER, text = "Message to be cleared 1")
        )
        chatRepo.insertMessage(
            ChatMessageEntity(sender = Sender.AI, text = "Message to be cleared 2")
        )

        val beforeCount = chatRepo.getGlobalMessages().first().size
        assertEquals(2, beforeCount)

        // Trigger Clear Chat
        auraViewModel.setActiveProject(null)
        auraViewModel.clearCurrentChatDirect()

        val afterCount = chatRepo.getGlobalMessages().first().size
        assertEquals(0, afterCount)
    }

    @Test
    fun `clearCurrentChat deletes project messages from Room database when scoped to a project`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val db = AppDatabase.getInstance(app)
        val chatRepo = DefaultChatRepository(db.chatMessageDao())
        val projectRepo = com.example.data.repository.DefaultProjectRepository(db.projectDao())
        val auraViewModel = com.example.viewmodel.AuraViewModel(app, chatRepo, projectRepository = projectRepo)

        chatRepo.clearGlobalChat()

        val proj = com.example.data.model.ProjectEntity(
            title = "Test Project For Clear",
            description = "Scoped conversation testing"
        )
        val projId = projectRepo.insertProject(proj)
        val persistedProj = projectRepo.getProjectById(projId)!!

        // Insert 1 global message and 2 project messages
        chatRepo.insertMessage(
            ChatMessageEntity(sender = Sender.USER, text = "Global message that should stay")
        )
        chatRepo.insertMessage(
            ChatMessageEntity(sender = Sender.USER, text = "Project message 1", associatedProjectId = projId)
        )
        chatRepo.insertMessage(
            ChatMessageEntity(sender = Sender.AI, text = "Project message 2", associatedProjectId = projId)
        )

        assertEquals(1, chatRepo.getGlobalMessages().first().size)
        assertEquals(2, chatRepo.getMessagesForProject(projId).first().size)

        // Set active project and trigger Clear Chat
        auraViewModel.setActiveProject(persistedProj)
        auraViewModel.clearCurrentChatDirect()

        // Assert project messages are wiped from Room
        assertEquals(0, chatRepo.getMessagesForProject(projId).first().size)
        // Assert global messages are preserved in Room
        assertEquals(1, chatRepo.getGlobalMessages().first().size)
    }

    @Test
    fun `dashboard integration state combines projects, pending approvals, and memories`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val db = AppDatabase.getInstance(app)
        val projectRepo = com.example.data.repository.DefaultProjectRepository(db.projectDao())
        val memoryRepo = com.example.data.repository.DefaultMemoryRepository(db.memoryDao())
        val auraViewModel = com.example.viewmodel.AuraViewModel(
            application = app,
            memoryRepository = memoryRepo,
            projectRepository = projectRepo
        )

        val initialProjects = auraViewModel.allProjects.first()
        val initialMemories = auraViewModel.allMemories.first()
        val pendingCount = auraViewModel.pendingApprovalCount.first()

        assertNotNull(initialProjects)
        assertNotNull(initialMemories)
        assertTrue(pendingCount >= 0)
    }

    @Test
    fun `auraViewModel exposes isGenerating state flow for typing indicator`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val auraViewModel = com.example.viewmodel.AuraViewModel(app)
        assertEquals(false, auraViewModel.isGenerating.value)
        assertEquals(null, auraViewModel.currentStreamingText.value)
    }

    @Test
    fun `chatRepository manages chat sessions and limits to last 5 recent sessions`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = AppDatabase.getInstance(context)
        val sessionDao = db.chatSessionDao()
        val messageDao = db.chatMessageDao()
        val repository = DefaultChatRepository(messageDao, sessionDao)

        // Create 6 sessions
        for (i in 1..6) {
            repository.createNewSession(title = "Session $i")
        }

        // Test getLastSessions(5) returns exactly 5
        val recent5 = repository.getLastSessions(5).first()
        assertEquals(5, recent5.size)

        // Verify ordering: newest modified first (Session 6 should be first)
        assertEquals("Session 6", recent5.first().title)

        // Delete a session
        val targetSession = recent5.first()
        repository.deleteSession(targetSession.id)

        val updated = repository.getLastSessions(5).first()
        assertTrue(updated.none { it.id == targetSession.id })
    }

    @Test
    fun `auraViewModel switches between chat sessions and loads session messages`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val db = AppDatabase.getInstance(app)
        val repository = DefaultChatRepository(db.chatMessageDao(), db.chatSessionDao())
        val auraViewModel = com.example.viewmodel.AuraViewModel(app, repository)

        // Create 2 test sessions
        val s1Id = repository.createNewSession("Research Session")
        val s2Id = repository.createNewSession("Coding Session")

        val s1 = repository.getSessionById(s1Id)!!
        val s2 = repository.getSessionById(s2Id)!!

        // Insert distinct messages for each session
        repository.insertMessage(
            ChatMessageEntity(sender = Sender.USER, text = "Research question 1", sessionId = s1Id)
        )
        repository.insertMessage(
            ChatMessageEntity(sender = Sender.USER, text = "Coding question 1", sessionId = s2Id)
        )

        // Switch to Session 1
        auraViewModel.selectSession(s1)
        assertEquals(s1Id, auraViewModel.activeSession.value?.id)

        val s1Messages = repository.getMessagesForSession(s1Id).first()
        assertEquals(1, s1Messages.size)
        assertEquals("Research question 1", s1Messages.first().text)

        // Switch to Session 2
        auraViewModel.selectSession(s2)
        assertEquals(s2Id, auraViewModel.activeSession.value?.id)

        val s2Messages = repository.getMessagesForSession(s2Id).first()
        assertEquals(1, s2Messages.size)
        assertEquals("Coding question 1", s2Messages.first().text)
    }

    @Test
    fun `appDatabase manages singleton connection and in-memory test database`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()

        // 1. Singleton connection management
        val db1 = AppDatabase.getInstance(context)
        val db2 = AppDatabase.getInstance(context)
        assertEquals(db1, db2)
        assertNotNull(db1.memoryDao())
        assertNotNull(db1.projectDao())

        // 2. In-memory connection management
        val inMemoryDb = AppDatabase.createInMemory(context)
        assertNotNull(inMemoryDb.memoryDao())
        val memoryId = inMemoryDb.memoryDao().insertMemory(
            com.example.data.model.MemoryEntity(
                content = "Ephemeral test memory",
                category = com.example.data.model.MemoryCategory.CONTEXT,
                importance = 5
            )
        )
        assertTrue(memoryId > 0)
        assertEquals(1, inMemoryDb.memoryDao().getActiveMemories().size)
        inMemoryDb.close()
    }

    @Test
    fun `memoryDao performs CRUD operations on MemoryItem entity`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = AppDatabase.createInMemory(context)
        val dao = db.memoryDao()

        // 1. Insert MemoryItem
        val item = com.example.data.model.MemoryItem(
            content = "User loves Kotlin multiplatform and Room persistence",
            category = com.example.data.model.MemoryCategory.PREFERENCE,
            importance = 4,
            isActive = true
        )
        val id = dao.insert(item)
        assertTrue(id > 0)

        // 2. Query MemoryItem by ID
        val retrieved = dao.getMemory(id).first()
        assertNotNull(retrieved)
        assertEquals("User loves Kotlin multiplatform and Room persistence", retrieved?.content)
        assertEquals(com.example.data.model.MemoryCategory.PREFERENCE, retrieved?.category)

        // 3. Query all memories
        val all = dao.getAllMemories().first()
        assertEquals(1, all.size)

        // 4. Update MemoryItem
        val updated = retrieved!!.copy(content = "User loves Jetpack Compose with Room", importance = 5)
        dao.update(updated)

        val retrievedUpdated = dao.getMemory(id).first()
        assertEquals("User loves Jetpack Compose with Room", retrievedUpdated?.content)
        assertEquals(5, retrievedUpdated?.importance)

        // 5. Delete MemoryItem
        dao.delete(retrievedUpdated!!)
        val afterDelete = dao.getMemory(id).first()
        assertEquals(null, afterDelete)

        // 6. Test deleteById
        val id2 = dao.insert(
            com.example.data.model.MemoryItem(
                content = "Second memory to delete by ID",
                category = com.example.data.model.MemoryCategory.FACT
            )
        )
        assertTrue(id2 > 0)
        dao.deleteById(id2)
        val afterDeleteById = dao.getMemory(id2).first()
        assertEquals(null, afterDeleteById)

        db.close()
    }

    @Test
    fun `clearing memories leaves chats and projects untouched`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = AppDatabase.getInstance(context)
        val memoryRepository = com.example.data.repository.DefaultMemoryRepository(db.memoryDao())
        val chatRepository = DefaultChatRepository(db.chatMessageDao())
        val projectRepository = com.example.data.repository.DefaultProjectRepository(db.projectDao())

        memoryRepository.clearAllMemories()
        chatRepository.clearGlobalChat()

        memoryRepository.insertMemory(
            com.example.data.model.MemoryEntity(
                content = "Memory to clear",
                category = com.example.data.model.MemoryCategory.PREFERENCE
            )
        )
        chatRepository.insertMessage(
            ChatMessageEntity(sender = Sender.USER, text = "Chat that must remain")
        )
        val projectId = projectRepository.insertProject(
            com.example.data.model.ProjectEntity(
                title = "Project that must remain",
                description = "Regression test project"
            )
        )

        memoryRepository.clearAllMemories()

        assertEquals(0, memoryRepository.getMemoriesCount().first())
        assertTrue(chatRepository.getGlobalMessages().first().any { it.text == "Chat that must remain" })
        assertNotNull(projectRepository.getProjectById(projectId))
    }


    @Test
    fun `startup creates one empty conversation and never reseeds demo data`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val db = AppDatabase.createInMemory(app)
        val chatRepo = DefaultChatRepository(db.chatMessageDao(), db.chatSessionDao())
        val memoryRepo = com.example.data.repository.DefaultMemoryRepository(db.memoryDao())
        val projectRepo = com.example.data.repository.DefaultProjectRepository(db.projectDao())
        app.getSharedPreferences("aura_memory_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("memory_initialized", true).commit()

        val firstViewModel = com.example.viewmodel.AuraViewModel(
            application = app,
            chatRepository = chatRepo,
            memoryRepository = memoryRepo,
            projectRepository = projectRepo
        )
        withTimeout(5_000) { firstViewModel.activeSession.first { it != null } }

        val sessionsAfterFirstStart = chatRepo.getAllSessions().first()
        assertEquals(1, sessionsAfterFirstStart.size)
        assertEquals("New Conversation", sessionsAfterFirstStart.single().title)
        assertTrue(chatRepo.getMessagesForSession(sessionsAfterFirstStart.single().id).first().isEmpty())
        assertTrue(projectRepo.getAllProjectsSnapshot().isEmpty())

        val secondViewModel = com.example.viewmodel.AuraViewModel(
            application = app,
            chatRepository = chatRepo,
            memoryRepository = memoryRepo,
            projectRepository = projectRepo
        )
        withTimeout(5_000) { secondViewModel.activeSession.first { it != null } }

        assertEquals(1, chatRepo.getAllSessions().first().size)
        assertTrue(projectRepo.getAllProjectsSnapshot().isEmpty())
        db.close()
    }

    @Test
    fun `startup reuses existing conversations without adding demo sessions`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val db = AppDatabase.createInMemory(app)
        val chatRepo = DefaultChatRepository(db.chatMessageDao(), db.chatSessionDao())
        val memoryRepo = com.example.data.repository.DefaultMemoryRepository(db.memoryDao())
        val projectRepo = com.example.data.repository.DefaultProjectRepository(db.projectDao())
        app.getSharedPreferences("aura_memory_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("memory_initialized", true).commit()

        val existingId = chatRepo.createNewSession("My Existing Conversation")
        chatRepo.insertMessage(
            ChatMessageEntity(sender = Sender.USER, text = "Keep this conversation", sessionId = existingId)
        )

        val viewModel = com.example.viewmodel.AuraViewModel(
            application = app,
            chatRepository = chatRepo,
            memoryRepository = memoryRepo,
            projectRepository = projectRepo
        )
        val active = withTimeout(5_000) { viewModel.activeSession.first { it != null } }

        assertEquals(existingId, active?.id)
        assertEquals(1, chatRepo.getAllSessions().first().size)
        assertEquals(
            "Keep this conversation",
            chatRepo.getMessagesForSession(existingId).first().single().text
        )
        assertTrue(projectRepo.getAllProjectsSnapshot().isEmpty())
        db.close()
    }

}
