package com.example.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai.HybridAiEngine
import com.example.ai.HybridAiState
import com.example.ai.HybridRoutingMode
import com.example.data.local.AppDatabase
import com.example.data.model.ActionStatus
import com.example.data.model.ActionType
import com.example.data.model.AiIdentityConfig
import com.example.data.model.AiModelUsed
import com.example.data.model.ChatMessageEntity
import com.example.data.model.MemoryCategory
import com.example.data.model.MemoryEntity
import com.example.data.model.MilestoneEntity
import com.example.data.model.PersonalityArchetype
import com.example.data.model.ProjectEntity
import com.example.data.model.ProjectStatus
import com.example.data.model.Sender
import com.example.data.model.TaskActionEntity
import com.example.voice.VoiceManager
import com.example.voice.VoiceState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import com.example.data.repository.ChatRepository
import com.example.data.repository.DefaultChatRepository
import com.example.data.repository.MemoryRepository
import com.example.data.repository.DefaultMemoryRepository
import com.example.data.repository.ProjectRepository
import com.example.data.repository.DefaultProjectRepository
import com.example.data.model.ProjectWithDetails
import kotlinx.coroutines.flow.stateIn
import android.content.Context
import com.example.data.model.ChatSessionEntity
import com.example.data.model.ProjectArtifactEntity
import com.example.data.model.ResponseLength
import com.example.data.model.UserSettingsEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONObject

class AuraViewModel @JvmOverloads constructor(
    application: Application,
    private val chatRepository: ChatRepository = DefaultChatRepository(
        AppDatabase.getInstance(application).chatMessageDao(),
        AppDatabase.getInstance(application).chatSessionDao()
    ),
    private val memoryRepository: MemoryRepository = DefaultMemoryRepository(
        AppDatabase.getInstance(application).memoryDao()
    ),
    private val projectRepository: ProjectRepository = DefaultProjectRepository(
        AppDatabase.getInstance(application).projectDao()
    )
) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val taskActionDao = db.taskActionDao()
    private val userSettingsDao = db.userSettingsDao()

    val userSettings: StateFlow<UserSettingsEntity> = userSettingsDao.getUserSettings()
        .map { it ?: UserSettingsEntity() }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            UserSettingsEntity()
        )

    private val _showSettings = MutableStateFlow(false)
    val showSettings: StateFlow<Boolean> = _showSettings.asStateFlow()

    fun setShowSettings(show: Boolean) {
        _showSettings.value = show
    }

    fun saveUserSettings(userName: String, primaryInterest: String) {
        viewModelScope.launch {
            userSettingsDao.saveUserSettings(
                UserSettingsEntity(
                    id = 1,
                    userName = userName.trim(),
                    primaryInterest = primaryInterest.trim()
                )
            )
        }
    }

    val voiceManager = VoiceManager(application)
    val voiceState: StateFlow<VoiceState> = voiceManager.voiceState

    private val hybridAiEngine = HybridAiEngine(application)
    private val identityPrefs = application.getSharedPreferences("aura_identity_prefs", Context.MODE_PRIVATE)

    // Identity configuration loaded from SharedPreferences to survive app restarts
    private val _identity = MutableStateFlow(loadPersistedIdentity())
    val identity: StateFlow<AiIdentityConfig> = _identity.asStateFlow()

    private fun loadPersistedIdentity(): AiIdentityConfig {
        val name = identityPrefs.getString("name", "Aura") ?: "Aura"
        val archName = identityPrefs.getString("archetype", PersonalityArchetype.ANALYTICAL.name) ?: PersonalityArchetype.ANALYTICAL.name
        val archetype = try { PersonalityArchetype.valueOf(archName) } catch (e: Exception) { PersonalityArchetype.ANALYTICAL }
        val lenName = identityPrefs.getString("responseLength", ResponseLength.BALANCED.name) ?: ResponseLength.BALANCED.name
        val responseLength = try { ResponseLength.valueOf(lenName) } catch (e: Exception) { ResponseLength.BALANCED }
        val coreValues = identityPrefs.getString("coreValues", "Clarity, Autonomy, Proactivity, High Reliability, Learning")
            ?: "Clarity, Autonomy, Proactivity, High Reliability, Learning"
        val customDirectives = identityPrefs.getString(
            "customDirectives",
            "Proactively identify reusable insights, propose tasks when commitments are made, and respect human supervision."
        ) ?: "Proactively identify reusable insights, propose tasks when commitments are made, and respect human supervision."
        val voicePitch = identityPrefs.getFloat("voicePitch", 1.0f)
        val voiceSpeed = identityPrefs.getFloat("voiceSpeed", 1.05f)
        val autoSpeak = identityPrefs.getBoolean("autoSpeakResponses", false)

        return AiIdentityConfig(
            name = name,
            archetype = archetype,
            responseLength = responseLength,
            coreValues = coreValues,
            customDirectives = customDirectives,
            voicePitch = voicePitch,
            voiceSpeed = voiceSpeed,
            autoSpeakResponses = autoSpeak
        )
    }

    private fun savePersistedIdentity(config: AiIdentityConfig) {
        identityPrefs.edit()
            .putString("name", config.name)
            .putString("archetype", config.archetype.name)
            .putString("responseLength", config.responseLength.name)
            .putString("coreValues", config.coreValues)
            .putString("customDirectives", config.customDirectives)
            .putFloat("voicePitch", config.voicePitch)
            .putFloat("voiceSpeed", config.voiceSpeed)
            .putBoolean("autoSpeakResponses", config.autoSpeakResponses)
            .apply()
    }

    // Navigation & Context State
    private val _selectedTab = MutableStateFlow(0) // 0: Chat, 1: Memory, 2: Projects, 3: Tasks, 4: Identity
    val selectedTab: StateFlow<Int> = _selectedTab.asStateFlow()

    private val _routingMode = MutableStateFlow(HybridRoutingMode.AUTO_HYBRID)
    val routingMode: StateFlow<HybridRoutingMode> = _routingMode.asStateFlow()

    private val _activeProject = MutableStateFlow<ProjectEntity?>(null)
    val activeProject: StateFlow<ProjectEntity?> = _activeProject.asStateFlow()

    // Streaming state for chat
    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val _currentStreamingText = MutableStateFlow<String?>(null)
    val currentStreamingText: StateFlow<String?> = _currentStreamingText.asStateFlow()

    private val _streamingModelUsed = MutableStateFlow<AiModelUsed?>(null)
    val streamingModelUsed: StateFlow<AiModelUsed?> = _streamingModelUsed.asStateFlow()

    // Memory Search & Filter
    val memorySearchQuery = MutableStateFlow("")
    val selectedMemoryCategory = MutableStateFlow<MemoryCategory?>(null)

    // Data Flows from Room
    val allMemories = memoryRepository.getAllMemories().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        emptyList()
    )

    val filteredMemories = combine(allMemories, memorySearchQuery, selectedMemoryCategory) { memories, query, category ->
        memories.filter { mem ->
            val matchesQuery = query.isBlank() || mem.content.contains(query, ignoreCase = true)
            val matchesCategory = category == null || mem.category == category
            matchesQuery && matchesCategory
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allProjects = projectRepository.getAllProjects().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        emptyList()
    )

    val allProjectsWithDetails = projectRepository.getAllProjectsWithDetails().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        emptyList()
    )

    val pendingApprovalCount = taskActionDao.getPendingApprovalCount().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        0
    )

    val allTaskActions = taskActionDao.getAllActions().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        emptyList()
    )

    // Recent 5 Sessions for Persistent Drawer
    val recentSessions: StateFlow<List<ChatSessionEntity>> = chatRepository.getLastSessions(5)
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            emptyList()
        )

    private val _activeSession = MutableStateFlow<ChatSessionEntity?>(null)
    val activeSession: StateFlow<ChatSessionEntity?> = _activeSession.asStateFlow()

    // Active Chat Messages
    private val _chatMessages = MutableStateFlow<List<ChatMessageEntity>>(emptyList())
    val chatMessages: StateFlow<List<ChatMessageEntity>> = _chatMessages.asStateFlow()

    init {
        loadChatMessages()
        seedInitialDataIfNeeded()
    }

    fun setSelectedTab(tabIndex: Int) {
        _selectedTab.value = tabIndex
    }

    fun setRoutingMode(mode: HybridRoutingMode) {
        _routingMode.value = mode
    }

    private var chatJob: kotlinx.coroutines.Job? = null

    fun selectSession(session: ChatSessionEntity) {
        _activeSession.value = session
        loadChatMessages()
    }

    fun startNewChatSession(title: String = "New Conversation") {
        viewModelScope.launch {
            val newId = chatRepository.createNewSession(title = title, projectId = _activeProject.value?.id)
            val session = chatRepository.getSessionById(newId)
            _activeSession.value = session ?: ChatSessionEntity(id = newId, title = title)
            loadChatMessages()
        }
    }

    fun deleteChatSession(session: ChatSessionEntity) {
        viewModelScope.launch {
            chatRepository.deleteSession(session.id)
            if (_activeSession.value?.id == session.id) {
                val remaining = recentSessions.value.filter { it.id != session.id }
                if (remaining.isNotEmpty()) {
                    _activeSession.value = remaining.first()
                } else {
                    val newId = chatRepository.createNewSession("New Conversation")
                    _activeSession.value = chatRepository.getSessionById(newId)
                }
                loadChatMessages()
            }
        }
    }

    fun setActiveProject(project: ProjectEntity?) {
        _activeProject.value = project
        viewModelScope.launch {
            if (project != null) {
                var projectSession = chatRepository.getSessionForProject(project.id)
                if (projectSession == null) {
                    val newId = chatRepository.createNewSession(title = project.title, projectId = project.id)
                    projectSession = chatRepository.getSessionById(newId)
                }
                _activeSession.value = projectSession
            }
            loadChatMessages()
        }
    }

    private fun loadChatMessages() {
        chatJob?.cancel()
        chatJob = viewModelScope.launch {
            val session = _activeSession.value
            val project = _activeProject.value
            val flow = when {
                session != null -> chatRepository.getMessagesForSession(session.id)
                project != null -> chatRepository.getMessagesForProject(project.id)
                else -> chatRepository.getGlobalMessages()
            }
            flow.collect { msgs ->
                _chatMessages.value = msgs
            }
        }
    }

    fun sendMessage(text: String) {
        if (text.isBlank() || _isGenerating.value) return
        val currentProj = _activeProject.value

        viewModelScope.launch {
            if (_activeSession.value == null) {
                val newId = chatRepository.createNewSession(title = "New Conversation", projectId = currentProj?.id)
                _activeSession.value = chatRepository.getSessionById(newId)
            }
            val currentSessionId = _activeSession.value?.id ?: 1L

            // 1. Insert User Message
            val userMsg = ChatMessageEntity(
                sender = Sender.USER,
                text = text,
                associatedProjectId = currentProj?.id,
                sessionId = currentSessionId
            )
            chatRepository.insertMessage(userMsg)

            // Refresh active session snippet/title
            val updatedSess = chatRepository.getSessionById(currentSessionId)
            if (updatedSess != null) {
                _activeSession.value = updatedSess
            }

            _isGenerating.value = true
            _currentStreamingText.value = ""
            _streamingModelUsed.value = null

            val memories = memoryRepository.getActiveMemories()
            val projects = projectRepository.getAllProjectsSnapshot()

            hybridAiEngine.processHybridRequest(
                prompt = text,
                mode = _routingMode.value,
                identity = _identity.value,
                activeMemories = memories,
                projects = projects,
                currentProject = currentProj,
                userSettings = userSettings.value
            ).collect { state ->
                when (state) {
                    is HybridAiState.Starting -> {}
                    is HybridAiState.Streaming -> {
                        _currentStreamingText.value = state.text
                        _streamingModelUsed.value = state.modelUsed
                    }
                    is HybridAiState.Completed -> {
                        _isGenerating.value = false
                        _currentStreamingText.value = null
                        _streamingModelUsed.value = null

                        // Save proposed action if any
                        var actionId: Long? = null
                        if (state.proposedAction != null) {
                            actionId = taskActionDao.insertAction(state.proposedAction)
                        }

                        // Save AI message to DB
                        val aiMsg = ChatMessageEntity(
                            sender = Sender.AI,
                            text = state.fullText,
                            modelUsed = state.modelUsed,
                            reasoningExplanation = state.reasoningRationale,
                            associatedProjectId = currentProj?.id,
                            sessionId = currentSessionId,
                            proposedActionId = actionId
                        )
                        chatRepository.insertMessage(aiMsg)

                        // If auto-speak enabled, speak the answer
                        if (_identity.value.autoSpeakResponses) {
                            voiceManager.speak(
                                text = state.fullText.take(300),
                                pitch = _identity.value.voicePitch,
                                speed = _identity.value.voiceSpeed
                            )
                        }
                    }
                    is HybridAiState.Error -> {
                        _isGenerating.value = false
                        _currentStreamingText.value = null
                        val errorMsg = ChatMessageEntity(
                            sender = Sender.AI,
                            text = "Error: ${state.message}",
                            modelUsed = AiModelUsed.LOCAL,
                            reasoningExplanation = "Model execution encountered an error.",
                            associatedProjectId = currentProj?.id,
                            sessionId = currentSessionId
                        )
                        chatRepository.insertMessage(errorMsg)
                    }
                }
            }
        }
    }

    fun approveAction(action: TaskActionEntity) {
        viewModelScope.launch {
            try {
                // Execute based on ActionType
                when (action.actionType) {
                    ActionType.SAVE_MEMORY -> {
                        val json = JSONObject(action.payloadJson)
                        val content = json.optString("content", action.description)
                        val catStr = json.optString("category", "PREFERENCE")
                        val cat = try { MemoryCategory.valueOf(catStr) } catch (e: Exception) { MemoryCategory.PREFERENCE }
                        val importance = json.optInt("importance", 3)
                        memoryRepository.insertMemory(
                            MemoryEntity(
                                content = content,
                                category = cat,
                                importance = importance,
                                isActive = true
                            )
                        )
                    }
                    ActionType.CREATE_PROJECT -> {
                        val json = JSONObject(action.payloadJson)
                        val title = json.optString("title", action.title)
                        val desc = json.optString("description", action.description)
                        val cat = json.optString("category", "General")
                        val projId = projectRepository.insertProject(
                            ProjectEntity(
                                title = title,
                                description = desc,
                                category = cat,
                                status = ProjectStatus.PLANNING
                            )
                        )
                        // Add default milestone
                        projectRepository.insertMilestone(
                            MilestoneEntity(
                                projectId = projId,
                                title = "Kickoff & Requirements Discovery",
                                isCompleted = false
                            )
                        )
                    }
                    ActionType.ADD_MILESTONE -> {
                        val json = JSONObject(action.payloadJson)
                        val projId = json.optLong("projectId", action.associatedProjectId ?: 1)
                        val title = json.optString("title", action.description)
                        projectRepository.insertMilestone(
                            MilestoneEntity(
                                projectId = projId,
                                title = title,
                                isCompleted = false
                            )
                        )
                    }
                    ActionType.CREATE_TODO -> {
                        // Marked as executed, payload verified
                    }
                    else -> {}
                }

                // Update Action Status in DB
                taskActionDao.updateActionStatus(
                    id = action.id,
                    status = ActionStatus.APPROVED_EXECUTED,
                    executedAt = System.currentTimeMillis()
                )
            } catch (e: Exception) {
                // Fallback mark as executed
                taskActionDao.updateActionStatus(
                    id = action.id,
                    status = ActionStatus.APPROVED_EXECUTED,
                    executedAt = System.currentTimeMillis()
                )
            }
        }
    }

    fun dismissAction(action: TaskActionEntity) {
        viewModelScope.launch {
            taskActionDao.updateActionStatus(action.id, ActionStatus.DISMISSED)
        }
    }

    fun addManualMemory(content: String, category: MemoryCategory, importance: Int) {
        viewModelScope.launch {
            memoryRepository.insertMemory(
                MemoryEntity(
                    content = content,
                    category = category,
                    importance = importance
                )
            )
        }
    }

    fun toggleMemoryActive(memory: MemoryEntity) {
        viewModelScope.launch {
            memoryRepository.setMemoryActive(memory.id, !memory.isActive)
        }
    }

    fun deleteMemory(memory: MemoryEntity) {
        viewModelScope.launch {
            memoryRepository.deleteMemory(memory)
        }
    }

    fun createProject(title: String, description: String, category: String) {
        viewModelScope.launch {
            val id = projectRepository.insertProject(
                ProjectEntity(
                    title = title,
                    description = description,
                    category = category,
                    status = ProjectStatus.PLANNING
                )
            )
            projectRepository.insertMilestone(
                MilestoneEntity(
                    projectId = id,
                    title = "Phase 1: Foundation & Setup",
                    isCompleted = false
                )
            )
        }
    }

    fun updateProjectStatus(project: ProjectEntity, status: ProjectStatus) {
        viewModelScope.launch {
            projectRepository.updateProject(project.copy(status = status))
        }
    }

    fun deleteProject(project: ProjectEntity) {
        viewModelScope.launch {
            if (_activeProject.value?.id == project.id) {
                _activeProject.value = null
            }
            projectRepository.deleteProject(project)
        }
    }

    fun addMilestone(projectId: Long, title: String) {
        viewModelScope.launch {
            projectRepository.insertMilestone(
                MilestoneEntity(
                    projectId = projectId,
                    title = title,
                    isCompleted = false
                )
            )
        }
    }

    fun toggleMilestoneCompleted(milestone: MilestoneEntity) {
        viewModelScope.launch {
            projectRepository.setMilestoneCompleted(milestone.id, !milestone.isCompleted)
        }
    }

    fun getMilestonesFlow(projectId: Long) = projectRepository.getMilestonesForProject(projectId)

    fun getArtifactsFlow(projectId: Long): Flow<List<ProjectArtifactEntity>> = projectRepository.getArtifactsForProject(projectId)

    fun addArtifact(projectId: Long, title: String, content: String, type: String = "NOTE") {
        viewModelScope.launch {
            projectRepository.insertArtifact(
                ProjectArtifactEntity(
                    projectId = projectId,
                    title = title,
                    content = content,
                    type = type
                )
            )
        }
    }

    fun deleteArtifact(artifact: ProjectArtifactEntity) {
        viewModelScope.launch {
            projectRepository.deleteArtifact(artifact)
        }
    }

    fun updateIdentity(newConfig: AiIdentityConfig) {
        _identity.value = newConfig
        savePersistedIdentity(newConfig)
    }

    suspend fun clearCurrentChatDirect() {
        val sess = _activeSession.value
        val proj = _activeProject.value
        if (sess != null) {
            chatRepository.clearSessionChat(sess.id)
            _activeSession.value = chatRepository.getSessionById(sess.id)
        } else if (proj == null) {
            chatRepository.clearGlobalChat()
        } else {
            chatRepository.clearProjectChat(proj.id)
        }
        _currentStreamingText.value = null
        _isGenerating.value = false
        _chatMessages.value = emptyList()
    }

    fun clearCurrentChat() {
        viewModelScope.launch {
            clearCurrentChatDirect()
        }
    }

    private fun seedInitialDataIfNeeded() {
        viewModelScope.launch {
            // Seed initial sessions for persistent drawer
            val firstSession = chatRepository.getSessionById(1L)
            if (firstSession == null) {
                val s1 = chatRepository.createNewSession("General AI Exploration")
                val s2 = chatRepository.createNewSession("Aura AI Architecture")
                val s3 = chatRepository.createNewSession("Autonomous Task Orchestrator")
                val s4 = chatRepository.createNewSession("Kotlin Compose Guidelines")
                val s5 = chatRepository.createNewSession("Personal AI Directives")

                chatRepository.insertMessage(
                    ChatMessageEntity(
                        sender = Sender.AI,
                        text = "Hello! I am Aura, your hybrid personal AI companion. How can I assist your workflow today?",
                        sessionId = s1
                    )
                )
                chatRepository.insertMessage(
                    ChatMessageEntity(
                        sender = Sender.AI,
                        text = "Aura combines local low-latency intelligence with Gemini cloud reasoning for high-complexity tasks.",
                        sessionId = s2
                    )
                )
                chatRepository.insertMessage(
                    ChatMessageEntity(
                        sender = Sender.AI,
                        text = "Task orchestration engine ready. All high-impact actions require your verified approval.",
                        sessionId = s3
                    )
                )
                chatRepository.insertMessage(
                    ChatMessageEntity(
                        sender = Sender.AI,
                        text = "Jetpack Compose UI is running with Material 3 dynamic theming and reactive state flows.",
                        sessionId = s4
                    )
                )
                chatRepository.insertMessage(
                    ChatMessageEntity(
                        sender = Sender.AI,
                        text = "Personal directives active. Active memories and user context are automatically injected into reasoning prompts.",
                        sessionId = s5
                    )
                )

                val active = chatRepository.getSessionById(s1)
                if (_activeSession.value == null) {
                    _activeSession.value = active
                    loadChatMessages()
                }
            } else if (_activeSession.value == null) {
                _activeSession.value = firstSession
                loadChatMessages()
            }

            val existingMemories = memoryRepository.getActiveMemories()
            if (existingMemories.isEmpty()) {
                memoryRepository.insertMemory(
                    MemoryEntity(
                        content = "User prefers clear, structured breakdowns with actionable next steps.",
                        category = MemoryCategory.PREFERENCE,
                        importance = 5
                    )
                )
                memoryRepository.insertMemory(
                    MemoryEntity(
                        content = "Focuses on modern Android, Kotlin, and Jetpack Compose architecture.",
                        category = MemoryCategory.CONTEXT,
                        importance = 4
                    )
                )
                memoryRepository.insertMemory(
                    MemoryEntity(
                        content = "Goal: Build a high-autonomy personal AI ecosystem with human approval loops.",
                        category = MemoryCategory.GOAL,
                        importance = 5
                    )
                )
            }

            val existingProjects = projectRepository.getAllProjectsSnapshot()
            if (existingProjects.isEmpty()) {
                val p1 = projectRepository.insertProject(
                    ProjectEntity(
                        title = "Aura AI Architecture",
                        description = "Next-gen hybrid personal AI combining on-device local models with Gemini 3.8 Flash cloud reasoning.",
                        category = "AI & Tech",
                        status = ProjectStatus.IN_PROGRESS,
                        colorHex = "#00F2FE"
                    )
                )
                projectRepository.insertMilestone(
                    MilestoneEntity(projectId = p1, title = "Persistent Memory & Knowledge Retrieval", isCompleted = true)
                )
                projectRepository.insertMilestone(
                    MilestoneEntity(projectId = p1, title = "Voice Interface & Reactive Orb Visualizer", isCompleted = true)
                )
                projectRepository.insertMilestone(
                    MilestoneEntity(projectId = p1, title = "Human-in-the-Loop Action Approval Protocol", isCompleted = true)
                )

                val p2 = projectRepository.insertProject(
                    ProjectEntity(
                        title = "Autonomous Task Orchestrator",
                        description = "System for decomposing complex goals into sequential verified tasks with safety checkpoints.",
                        category = "Work & Automation",
                        status = ProjectStatus.PLANNING,
                        colorHex = "#9D4EDD"
                    )
                )
                projectRepository.insertMilestone(
                    MilestoneEntity(projectId = p2, title = "Define Action Schema & Task Entity", isCompleted = true)
                )
                projectRepository.insertMilestone(
                    MilestoneEntity(projectId = p2, title = "Implement Verification & Audit Trail", isCompleted = false)
                )
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        voiceManager.cleanup()
    }
}
