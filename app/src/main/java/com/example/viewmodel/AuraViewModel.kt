package com.example.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai.CloudHistory
import com.example.ai.HybridAiEngine
import com.example.ai.HybridAiState
import com.example.ai.HybridRoutingMode
import com.example.data.local.AppDatabase
import com.example.BuildConfig
import com.example.actions.ActionExecutor
import com.example.auth.FirebaseRelayAuth
import com.example.data.model.ActionType
import com.example.data.model.AiIdentityConfig
import com.example.data.model.AiModelUsed
import com.example.data.model.ChatMessageEntity
import com.example.data.model.MemoryCategory
import com.example.data.model.MemoryEntity
import com.example.data.model.MemorySource
import com.example.data.model.TodoEntity
import com.example.data.model.TodoRules
import com.example.data.model.TodoSource
import com.example.data.model.movedToDeleted
import com.example.data.model.restored
import com.example.data.model.toggledDone
import com.example.data.model.edited
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

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
    private val todoDao = db.todoDao()
    private val actionExecutor = ActionExecutor(db)
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
    private val memoryPrefs = application.getSharedPreferences("aura_memory_prefs", Context.MODE_PRIVATE)

    // Cloud chat is opt-in. Personal details go only with the separate sharing switch below.
    private val privacyPrefs = application.getSharedPreferences("aura_privacy_prefs", Context.MODE_PRIVATE)
    private val _cloudChatEnabled = MutableStateFlow(privacyPrefs.getBoolean("cloud_chat_enabled", false))
    val cloudChatEnabled: StateFlow<Boolean> = _cloudChatEnabled.asStateFlow()

    fun setCloudChatEnabled(enabled: Boolean) {
        privacyPrefs.edit().putBoolean("cloud_chat_enabled", enabled).apply()
        _cloudChatEnabled.value = enabled
    }

    // Off by default. When on, Claude also gets your name, main interest, and only the
    // memories you marked "Share with Claude". Projects never go.
    private val _sharePersonalContext = MutableStateFlow(privacyPrefs.getBoolean("share_personal_context", false))
    val sharePersonalContext: StateFlow<Boolean> = _sharePersonalContext.asStateFlow()

    /**
     * When the user stops sharing something, earlier messages in their chats may still quote it
     * (e.g. Claude listing a project's to-dos). From this moment, only newer messages are sent to
     * Claude as conversation history, so turning sharing off really stops it (owner report, #46).
     */
    private fun markSharingReduced() {
        privacyPrefs.edit().putLong(HISTORY_CUTOFF_KEY, System.currentTimeMillis()).apply()
    }

    private fun historyCutoff(): Long = privacyPrefs.getLong(HISTORY_CUTOFF_KEY, 0L)

    fun setSharePersonalContext(enabled: Boolean) {
        if (!enabled && _sharePersonalContext.value) markSharingReduced()
        privacyPrefs.edit().putBoolean("share_personal_context", enabled).apply()
        _sharePersonalContext.value = enabled
    }

    // On by default: Claude may suggest one change per reply (save memory, create project, add
    // milestone) as an approval card. Nothing happens unless the user approves it.
    private val _claudeProposals = MutableStateFlow(privacyPrefs.getBoolean("claude_proposals_enabled", true))
    val claudeProposals: StateFlow<Boolean> = _claudeProposals.asStateFlow()

    fun setClaudeProposals(enabled: Boolean) {
        privacyPrefs.edit().putBoolean("claude_proposals_enabled", enabled).apply()
        _claudeProposals.value = enabled
    }

    // Off by default: Claude sees titles and due dates of up to 30 open to-dos. Notes, finished
    // to-dos and projects stay on the phone.
    private val _shareTodos = MutableStateFlow(privacyPrefs.getBoolean("share_open_todos", false))
    val shareTodos: StateFlow<Boolean> = _shareTodos.asStateFlow()

    // Projects the user chose to share with Claude while chatting in them (off for every project
    // by default). Stored as IDs; removed when the project is deleted.
    private val _sharedProjectIds = MutableStateFlow(
        privacyPrefs.getStringSet("shared_project_ids", emptySet()).orEmpty().mapNotNull { it.toLongOrNull() }.toSet()
    )
    val sharedProjectIds: StateFlow<Set<Long>> = _sharedProjectIds.asStateFlow()

    fun setProjectShared(projectId: Long, shared: Boolean) {
        if (!shared && projectId in _sharedProjectIds.value) markSharingReduced()
        val next = if (shared) _sharedProjectIds.value + projectId else _sharedProjectIds.value - projectId
        privacyPrefs.edit().putStringSet("shared_project_ids", next.map { it.toString() }.toSet()).apply()
        _sharedProjectIds.value = next
    }

    fun setShareTodos(enabled: Boolean) {
        if (!enabled && _shareTodos.value) markSharingReduced()
        privacyPrefs.edit().putBoolean("share_open_todos", enabled).apply()
        _shareTodos.value = enabled
    }

    // Off by default: replies are read in the user's ElevenLabs voice. The text goes to ElevenLabs
    // through the relay; on any failure Aura says why and uses the phone's voice instead.
    private val _elevenLabsVoice = MutableStateFlow(privacyPrefs.getBoolean("elevenlabs_voice", false))
    val elevenLabsVoice: StateFlow<Boolean> = _elevenLabsVoice.asStateFlow()

    fun setElevenLabsVoice(enabled: Boolean) {
        privacyPrefs.edit().putBoolean("elevenlabs_voice", enabled).apply()
        _elevenLabsVoice.value = enabled
    }

    private val relaySpeech by lazy {
        com.example.voice.RelaySpeech(BuildConfig.AURA_RELAY_URL, relayAuth, getApplication<Application>().cacheDir)
    }
    private var speechJob: kotlinx.coroutines.Job? = null

    /**
     * Reads a reply aloud. The ElevenLabs voice is used only for replies that came from Claude
     * ([fromCloud]) and only when it is switched on; on failure the phone's voice reads it instead.
     * Replies answered on this phone are always read by the phone's voice, so text that was kept on
     * the phone (such as a list of memories) is never sent to ElevenLabs (audit 03, A3-02).
     */
    fun speakReply(markdown: String, fromCloud: Boolean) {
        val text = com.example.voice.SpeechText.prepare(markdown)
        if (text.isBlank()) return
        val pitch = _identity.value.voicePitch
        val speed = _identity.value.voiceSpeed
        speechJob?.cancel()
        voiceManager.stopAll()
        if (!fromCloud || !_elevenLabsVoice.value || !cloudChatEnabled.value) {
            phoneVoice(text, pitch, speed)
            return
        }
        voiceManager.markPreparing()
        speechJob = viewModelScope.launch {
            val audio = relaySpeech.fetch(text)
            val played = audio.getOrNull()?.let { voiceManager.playAudio(it) } == true
            if (!played) {
                val reason = audio.exceptionOrNull()?.message ?: "the audio couldn't be played"
                voiceNotice("ElevenLabs voice unavailable ($reason). Using the phone's voice.")
                phoneVoice(text, pitch, speed)
            }
        }
    }

    /** Stops whatever voice is running: listening, fetching an ElevenLabs reply, or speaking. */
    fun stopVoice() {
        speechJob?.cancel()
        speechJob = null
        voiceManager.stopAll()
    }

    private fun phoneVoice(text: String, pitch: Float, speed: Float) {
        if (!voiceManager.speak(text, pitch, speed)) voiceNotice("The phone's voice isn't ready yet. Try again in a moment.")
    }

    fun voiceNotice(message: String) {
        android.widget.Toast.makeText(getApplication(), message, android.widget.Toast.LENGTH_LONG).show()
    }

    // Encrypted backup and restore (audit 02, F-03). Nothing leaves the phone except to the file
    // the user picks; the passphrase is never stored.
    sealed interface BackupUiState {
        data object Idle : BackupUiState
        data class Working(val what: String) : BackupUiState
        data class Done(val message: String) : BackupUiState
        data class Failed(val message: String) : BackupUiState
        data class ConfirmRestore(val summary: com.example.backup.BackupManager.Summary) : BackupUiState
    }
    private val _backupState = MutableStateFlow<BackupUiState>(BackupUiState.Idle)
    val backupState: StateFlow<BackupUiState> = _backupState.asStateFlow()
    private val backupManager by lazy { com.example.backup.BackupManager(getApplication()) }
    private var pendingRestore: com.example.backup.BackupManager.Staged? = null

    fun dismissBackupMessage() {
        // Cancelling a restore removes the unlocked copy of the backup.
        pendingRestore?.discard()
        pendingRestore = null
        _backupState.value = BackupUiState.Idle
    }

    private fun backupError(e: Throwable): String = when (e) {
        is com.example.backup.BackupCrypto.BackupException -> e.message ?: "Something went wrong."
        is OutOfMemoryError -> "This phone ran out of memory. Close other apps and try again."
        else -> "Something went wrong (${e.message ?: e.javaClass.simpleName})."
    }

    fun exportBackup(uri: android.net.Uri, passphrase: CharArray) {
        _backupState.value = BackupUiState.Working("Encrypting your backup\u2026")
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            _backupState.value = try {
                backupManager.writeBackup(uri, passphrase)
                BackupUiState.Done("Backup saved. Keep the passphrase somewhere safe: without it the backup can't be opened.")
            } catch (e: Throwable) {
                BackupUiState.Failed("Backup not saved. ${backupError(e)}")
            } finally {
                passphrase.fill(' ')
            }
        }
    }

    fun inspectBackup(uri: android.net.Uri, passphrase: CharArray) {
        _backupState.value = BackupUiState.Working("Unlocking the backup\u2026")
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            _backupState.value = try {
                pendingRestore?.discard()
                val (summary, staged) = backupManager.inspect(uri, passphrase)
                pendingRestore = staged
                BackupUiState.ConfirmRestore(summary)
            } catch (e: Throwable) {
                BackupUiState.Failed("Nothing was changed. ${backupError(e)}")
            } finally {
                passphrase.fill(' ')
            }
        }
    }

    fun confirmRestore() {
        val entries = pendingRestore ?: return
        pendingRestore = null
        _backupState.value = BackupUiState.Working("Restoring\u2026 Aura will restart.")
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                backupManager.restore(entries)
                com.example.backup.BackupManager.restartApp(getApplication())
            } catch (e: Exception) {
                _backupState.value = BackupUiState.Failed(backupError(e))
            }
        }
    }

    // Why an approved action failed, shown on its card (kept for this session).
    private val _actionFailures = MutableStateFlow<Map<Long, String>>(emptyMap())
    val actionFailures: StateFlow<Map<Long, String>> = _actionFailures.asStateFlow()

    // Claude relay access: anonymous Firebase sign-in gives this install an "access ID",
    // which must be added to the relay's allowlist (AURA_ALLOWED_UIDS) before Claude answers.
    private val relayAuth = FirebaseRelayAuth()
    val relayConfigured: Boolean = BuildConfig.AURA_RELAY_URL.isNotBlank()
    private val _relayAccessId = MutableStateFlow(relayAuth.accessId())
    val relayAccessId: StateFlow<String?> = _relayAccessId.asStateFlow()
    private val _relayAccessStatus = MutableStateFlow<String?>(null)
    val relayAccessStatus: StateFlow<String?> = _relayAccessStatus.asStateFlow()

    fun requestRelayAccessId() {
        viewModelScope.launch {
            _relayAccessStatus.value = "Signing in\u2026"
            _relayAccessStatus.value = try {
                relayAuth.idToken()
                _relayAccessId.value = relayAuth.accessId()
                null
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                e.message ?: "Sign-in failed."
            }
        }
    }

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

    // ---- To-dos ----
    val visibleTodos: StateFlow<List<TodoEntity>> = todoDao.observeVisible()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val deletedTodos: StateFlow<List<TodoEntity>> = todoDao.observeDeleted()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val openTodoCount: StateFlow<Int> = todoDao.observeOpenCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    // Due-date reminders: one notification at the chosen hour on the due day. Rescheduled
    // whenever to-dos change (added, edited, finished, deleted, restored, approved from chat).
    private val reminderPrefs = com.example.reminders.ReminderPrefs(application)
    private val _remindersEnabled = MutableStateFlow(reminderPrefs.enabled)
    val remindersEnabled: StateFlow<Boolean> = _remindersEnabled.asStateFlow()
    private val _reminderHour = MutableStateFlow(reminderPrefs.hour)
    val reminderHour: StateFlow<Int> = _reminderHour.asStateFlow()

    fun setRemindersEnabled(enabled: Boolean) {
        reminderPrefs.enabled = enabled
        _remindersEnabled.value = enabled
        resyncReminders()
    }

    fun setReminderHour(hour: Int) {
        reminderPrefs.hour = hour
        _reminderHour.value = reminderPrefs.hour
        resyncReminders()
    }

    private fun resyncReminders() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            com.example.reminders.Reminders.sync(getApplication(), todoDao.getOpen())
        }
    }

    init {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            // Show anything that came due while the app was closed and the alarm didn't run.
            runCatching { com.example.reminders.Reminders.handleDue(getApplication()) }
            todoDao.observeVisible().collect { todos ->
                runCatching { com.example.reminders.Reminders.sync(getApplication(), todos.filter { !it.done }) }
            }
        }
    }

    /** Adds a to-do typed by the user. Returns false (and saves nothing) if the text breaks a limit. */
    fun addTodo(title: String, notes: String, dueEpochDay: Long?, projectId: Long?): Boolean {
        val (t, n) = TodoRules.clean(title, notes) ?: return false
        viewModelScope.launch {
            todoDao.insert(TodoEntity(title = t, notes = n, dueEpochDay = dueEpochDay, projectId = projectId, source = TodoSource.TYPED))
        }
        return true
    }

    fun editTodo(todo: TodoEntity, title: String, notes: String, dueEpochDay: Long?, projectId: Long?): Boolean {
        val updated = todo.edited(title, notes, dueEpochDay, projectId, System.currentTimeMillis()) ?: return false
        viewModelScope.launch { todoDao.update(updated) }
        return true
    }

    /** Ticks a to-do off, or reopens a finished one. */
    fun toggleTodoDone(todo: TodoEntity) {
        viewModelScope.launch { todoDao.update(todo.toggledDone(System.currentTimeMillis())) }
    }

    /** Moves a to-do to Recently deleted (restorable for 30 days). */
    fun deleteTodo(todo: TodoEntity) {
        viewModelScope.launch { todoDao.update(todo.movedToDeleted(System.currentTimeMillis())) }
    }

    fun restoreTodo(todo: TodoEntity) {
        viewModelScope.launch { todoDao.update(todo.restored()) }
    }

    /** Removes a to-do from Recently deleted for good. */
    fun deleteTodoForever(todo: TodoEntity) {
        viewModelScope.launch { todoDao.deleteForever(todo.id) }
    }

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
        // An action left EXECUTING means the app was killed mid-run; its writes were rolled back.
        viewModelScope.launch { actionExecutor.recoverInterrupted() }
        // Recently deleted to-dos are kept for 30 days, then removed for good.
        viewModelScope.launch { todoDao.purgeDeletedBefore(TodoRules.purgeCutoff(System.currentTimeMillis())) }
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
        // Leave the previous project at once, before anything can be sent: looking up the new
        // chat's project takes a moment, and a message sent meanwhile must not carry the old one
        // (audit 04, A4-02).
        if (_activeProject.value?.id != session.associatedProjectId) _activeProject.value = null
        loadChatMessages()
        // The chat decides the project: opening a project's chat makes that project active, and
        // opening any other chat leaves the project, so a shared project is only ever sent in its
        // own chat and the header always matches the chat on screen (audit 03, A3-08).
        viewModelScope.launch {
            val project = session.associatedProjectId?.let { projectRepository.getProjectById(it) }
            if (_activeSession.value?.id == session.id) _activeProject.value = project
        }
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
            if (_activeSession.value?.id == session.id) cancelSend()
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
            } else if (_activeSession.value?.associatedProjectId != null) {
                // Leaving a project also leaves its chat, so the chat on screen never belongs to a
                // project the header no longer shows. Goes to the latest general chat, or a new one.
                _activeSession.value = chatRepository.getAllSessions().firstOrNull()?.firstOrNull { it.associatedProjectId == null }
                    ?: chatRepository.getSessionById(chatRepository.createNewSession("New Conversation"))
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
        // Set before anything else runs, so a second tap can't send the message twice.
        _isGenerating.value = true
        _currentStreamingText.value = ""
        _streamingModelUsed.value = null

        val job = viewModelScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            val thisJob = coroutineContext[kotlinx.coroutines.Job]
            var sessionId: Long? = null
            try {
                if (_activeSession.value == null) {
                    val newId = chatRepository.createNewSession(title = "New Conversation", projectId = currentProj?.id)
                    _activeSession.value = chatRepository.getSessionById(newId)
                }
                val currentSessionId = _activeSession.value?.id ?: 1L
                sessionId = currentSessionId

                // Earlier Claude exchanges in this chat, read before the new message is saved.
                // Only used if this message goes to the cloud.
                val earlier = chatRepository.getMessagesForSession(currentSessionId).first()
                val linkedActions = earlier.mapNotNull { it.proposedActionId }.distinct()
                    .mapNotNull { taskActionDao.getActionById(it) }.associateBy { it.id }
                val cloudHistory = CloudHistory.fromMessages(earlier, actions = linkedActions, since = historyCutoff())
                val openTodosNow = if (shareTodos.value) todoDao.getOpen() else emptyList()
                val sharedTodoList = com.example.ai.CloudTodos.from(openTodosNow)
                val sharedTodoTotal = com.example.ai.CloudTodos.openCount(openTodosNow)
                val sharedProject = currentProj?.takeIf { it.id in sharedProjectIds.value }?.let { p ->
                    com.example.ai.CloudProjects.from(
                        project = p,
                        milestones = projectRepository.getMilestonesForProjectList(p.id),
                        todos = todoDao.getOpen(),
                        notes = projectRepository.getArtifactsForProject(p.id).first()
                    )
                }

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

                val memories = memoryRepository.getActiveMemories()
                val projects = projectRepository.getAllProjectsSnapshot()

                hybridAiEngine.processHybridRequest(
                    prompt = text,
                    mode = _routingMode.value,
                    identity = _identity.value,
                    activeMemories = memories,
                    projects = projects,
                    currentProject = currentProj,
                    userSettings = userSettings.value,
                    allowCloudRequest = cloudChatEnabled.value,
                    history = cloudHistory,
                    sharePersonalContext = sharePersonalContext.value,
                    allowProposals = claudeProposals.value,
                    sharedTodos = sharedTodoList,
                    sharedTodosTotal = sharedTodoTotal,
                    sharedProject = sharedProject
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
                                speakReply(state.fullText, fromCloud = state.modelUsed == AiModelUsed.CLOUD)
                            }
                        }
                        is HybridAiState.Error -> {
                            _isGenerating.value = false
                            _currentStreamingText.value = null
                            val errorMsg = ChatMessageEntity(
                                sender = Sender.AI,
                                text = "Error: ${state.message}",
                                modelUsed = AiModelUsed.NONE,
                                reasoningExplanation = "Model execution encountered an error.",
                                associatedProjectId = currentProj?.id,
                                sessionId = currentSessionId
                            )
                            chatRepository.insertMessage(errorMsg)
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Say what happened in the chat instead of leaving it waiting for a reply.
                android.util.Log.w("AuraViewModel", "Sending a message failed", e)
                val failedIn = sessionId
                if (failedIn != null) {
                    runCatching {
                        chatRepository.insertMessage(
                            ChatMessageEntity(
                                sender = Sender.AI,
                                text = "Aura couldn't finish this reply (${e.message ?: e.javaClass.simpleName}). Try sending it again.",
                                modelUsed = AiModelUsed.NONE,
                                reasoningExplanation = "Something went wrong inside Aura while handling this message.",
                                associatedProjectId = currentProj?.id,
                                sessionId = failedIn
                            )
                        )
                    }
                }
            } finally {
                // Whatever happened (finished, failed or cancelled), the chat must accept messages again.
                // (If the chat was cleared or deleted meanwhile, [cancelSend] already did this.)
                if (sendJob === thisJob) {
                    sendJob = null
                    _isGenerating.value = false
                    _currentStreamingText.value = null
                    _streamingModelUsed.value = null
                }
            }
        }
        sendJob = job
        job.start()
    }

    /** The reply being produced, if any. */
    private var sendJob: kotlinx.coroutines.Job? = null

    /** Stops the reply in progress (its chat was cleared or deleted) so it isn't saved afterwards. */
    private fun cancelSend() {
        sendJob?.cancel()
        sendJob = null
        _isGenerating.value = false
        _currentStreamingText.value = null
        _streamingModelUsed.value = null
    }

    /**
     * Runs an approved action. If the card's version differs from the stored one (the user edited
     * its fields), the edit is saved first, and only while the action is still waiting for approval.
     */
    fun approveAction(action: TaskActionEntity) {
        viewModelScope.launch {
            val current = taskActionDao.getActionById(action.id) ?: return@launch
            if (current.status != com.example.data.model.ActionStatus.PROPOSED) return@launch
            if (action != current) {
                taskActionDao.updateAction(action.copy(status = current.status, createdAt = current.createdAt))
            }
            // The executor records APPROVED_EXECUTED only when the operation succeeds, FAILED otherwise,
            // and refuses to run an action that is no longer PROPOSED (double tap, second screen).
            val result = actionExecutor.execute(action.id)
            if (result is com.example.actions.ActionResult.Failed) {
                _actionFailures.value = _actionFailures.value + (action.id to result.reason)
            }
        }
    }

    fun dismissAction(action: TaskActionEntity) {
        viewModelScope.launch {
            taskActionDao.dismissIfProposed(action.id)
        }
    }

    fun addManualMemory(content: String, category: MemoryCategory, importance: Int) {
        viewModelScope.launch {
            memoryRepository.insertMemory(
                MemoryEntity(
                    content = content.trim(),
                    category = category,
                    importance = importance,
                    source = MemorySource.TYPED
                )
            )
        }
    }

    /** Marks or unmarks a memory as OK to send to Claude. */
    fun toggleMemoryShare(memory: MemoryEntity) {
        viewModelScope.launch {
            if (memory.shareWithCloud) markSharingReduced()
            memoryRepository.updateMemory(memory.copy(shareWithCloud = !memory.shareWithCloud))
        }
    }

    /** Saves the user's edit of a memory; blank text is ignored. */
    fun editMemory(memory: MemoryEntity, content: String, category: MemoryCategory, importance: Int) {
        val updated = memory.edited(content, category, importance, System.currentTimeMillis()) ?: return
        viewModelScope.launch {
            memoryRepository.updateMemory(updated)
        }
    }

    fun toggleMemoryActive(memory: MemoryEntity) {
        viewModelScope.launch {
            memoryRepository.setMemoryActive(memory.id, !memory.isActive)
        }
    }

    fun deleteMemory(memory: MemoryEntity) {
        viewModelScope.launch {
            // A deliberate deletion must not cause starter memories to reappear.
            memoryPrefs.edit().putBoolean("memory_initialized", true).apply()
            memoryRepository.deleteMemory(memory)
        }
    }

    fun clearAllMemories() {
        viewModelScope.launch {
            // This operation is intentionally limited to the memories table.
            memoryPrefs.edit().putBoolean("memory_initialized", true).apply()
            memoryRepository.clearAllMemories()
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
            if (_activeSession.value?.associatedProjectId == project.id) cancelSend()
            projectRepository.deleteProject(project)
            // Its chats go with it: they can quote the project's notes (audit 03, A3-09).
            db.chatMessageDao().deleteForProjectChats(project.id)
            db.chatSessionDao().deleteForProject(project.id)
            val open = _activeSession.value
            if (open != null && chatRepository.getSessionById(open.id) == null) {
                val next = chatRepository.getAllSessions().firstOrNull()?.firstOrNull()
                    ?: chatRepository.getSessionById(chatRepository.createNewSession("New Conversation"))
                _activeSession.value = next
                _activeProject.value = next?.associatedProjectId?.let { projectRepository.getProjectById(it) }
                loadChatMessages()
            }
            // Its to-dos stay, just without a project.
            todoDao.clearProject(project.id)
            // A later project could reuse the ID; it must not inherit sharing.
            setProjectShared(project.id, false)
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

    fun updateArtifact(artifact: ProjectArtifactEntity, title: String, content: String) {
        if (title.isBlank()) return
        viewModelScope.launch { projectRepository.updateArtifact(artifact.copy(title = title.trim(), content = content)) }
    }

    /** Reads a text file or PDF the user picked into a project note (on the phone). Returns a reason on failure. */
    suspend fun readTextFile(uri: android.net.Uri): Result<Pair<String, String>> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            val resolver = getApplication<Application>().contentResolver
            val name = resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: "Imported file"
            val bytes = resolver.openInputStream(uri)?.use { input ->
                val buffer = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                while (true) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    buffer.write(chunk, 0, n)
                    if (buffer.size() > com.example.data.model.PdfText.MAX_BYTES) throw IllegalArgumentException("That file is larger than 10 MB.")
                }
                buffer.toByteArray()
            } ?: throw IllegalArgumentException("Couldn't open that file.")
            val text = if (com.example.data.model.PdfText.isPdf(bytes)) {
                com.example.data.model.PdfText.extract(getApplication(), bytes)
            } else {
                com.example.data.model.NoteImport.decode(bytes)
            }
            name to text
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
        cancelSend()
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
            // Never recreate demo conversations or welcome messages.
            // Reuse an existing conversation, or create one empty conversation
            // only when the user has none.
            val sessions = chatRepository.getAllSessions().first()
            val sessionToUse = sessions.firstOrNull()
                ?: chatRepository.createNewSession("New Conversation")
                    .let { chatRepository.getSessionById(it) }

            if (_activeSession.value == null && sessionToUse != null) {
                _activeSession.value = sessionToUse
                loadChatMessages()
            }

            // Never invent memories. Aura only stores what the user saves or approves.
            // Memories created by older versions are left alone; the user can delete them.

            // Do not recreate demo projects or milestones when the project list is empty.
        }
    }

    override fun onCleared() {
        super.onCleared()
        voiceManager.cleanup()
    }
}

/** Privacy setting: conversation history older than this (epoch ms) is never sent to Claude. */
private const val HISTORY_CUTOFF_KEY = "cloud_history_since"
