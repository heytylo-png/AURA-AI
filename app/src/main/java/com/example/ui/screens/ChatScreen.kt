package com.example.ui.screens

import com.example.ui.theme.auraBackground
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.ui.text.style.TextOverflow
import com.example.ui.components.ChatSessionsDrawerContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.core.content.ContextCompat
import com.example.ai.HybridRoutingMode
import com.example.data.model.AiModelUsed
import com.example.data.model.ChatMessageEntity
import com.example.data.model.Sender
import com.example.ui.components.ActionProposalCard
import com.example.ui.components.AiTypingIndicator
import com.example.ui.components.ModelBadge
import com.example.ui.components.MarkdownRenderer
import com.example.ui.components.MarkdownText
import com.example.ui.components.MessageNotice
import com.example.ui.components.NoticeBox
import com.example.ui.components.ThinkingRow
import com.example.ui.components.VoiceOrb
import com.example.viewmodel.AuraViewModel
import com.example.voice.VoiceState
import kotlinx.coroutines.launch

@Composable
fun ChatScreen(viewModel: AuraViewModel) {
    val context = LocalContext.current
    val isKeyboardVisible = androidx.compose.foundation.layout.WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val coroutineScope = rememberCoroutineScope()
    val identity by viewModel.identity.collectAsState()
    val messages by viewModel.chatMessages.collectAsState()
    val isGenerating by viewModel.isGenerating.collectAsState()
    val currentStreamingText by viewModel.currentStreamingText.collectAsState()
    val streamingModelUsed by viewModel.streamingModelUsed.collectAsState()
    val voiceState by viewModel.voiceState.collectAsState()
    val sharedProjectIds by viewModel.sharedProjectIds.collectAsState()
    val activeProject by viewModel.activeProject.collectAsState()
    val routingMode by viewModel.routingMode.collectAsState()
    val taskActions by viewModel.allTaskActions.collectAsState()
    val projectsForCards by viewModel.allProjects.collectAsState()
    val actionFailures by viewModel.actionFailures.collectAsState()
    val recentSessions by viewModel.recentSessions.collectAsState()
    val activeSession by viewModel.activeSession.collectAsState()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    var inputText by remember { mutableStateOf("") }
    var showClearChatDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    val totalItemCount = messages.size + if (isGenerating) 1 else 0
    val lastItemIndex = (totalItemCount - 1).coerceAtLeast(0)

    val isAtBottom by remember {
        derivedStateOf {
            val visibleItems = listState.layoutInfo.visibleItemsInfo
            if (visibleItems.isEmpty()) true
            else {
                val lastVisible = visibleItems.last()
                lastVisible.index >= totalItemCount - 1
            }
        }
    }

    // Voice fills the box as you speak; the user reviews it and taps send (audit SEC-13).
    fun startVoiceInput() {
        viewModel.voiceManager.startListening(
            onResult = { spoken -> inputText = spoken },
            onPartial = { partial -> inputText = partial },
            onError = { reason -> viewModel.voiceNotice(reason) }
        )
    }

    // Audio recording permission launcher
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            // Voice fills the box; the user reviews it and taps send (audit SEC-13).
            startVoiceInput()
        }
    }

    // Smoothly auto-scroll when new messages are added to the conversation
    LaunchedEffect(messages.size) {
        if (totalItemCount > 0) {
            listState.animateScrollToItem(lastItemIndex)
        }
    }

    // Continuously keep active streaming responses in view without animation jitter
    LaunchedEffect(isGenerating, currentStreamingText) {
        if (isGenerating && totalItemCount > 0) {
            listState.scrollToItem(lastItemIndex)
        }
    }

    // Auto-scroll when active project conversation context changes
    LaunchedEffect(activeProject?.id) {
        if (totalItemCount > 0) {
            listState.scrollToItem(lastItemIndex)
        }
    }

    if (showClearChatDialog) {
        AlertDialog(
            onDismissRequest = { showClearChatDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.DeleteSweep,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(28.dp)
                )
            },
            title = {
                Text(
                    text = if (activeProject != null) "Clear Project Chat?" else "Clear Conversation?",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = if (activeProject != null) {
                        "Are you sure you want to permanently delete all messages for project \"${activeProject?.title}\" from the Room database? This cannot be undone."
                    } else {
                        "Are you sure you want to permanently delete all messages in this conversation from the Room database? This cannot be undone."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearCurrentChat()
                        showClearChatDialog = false
                        coroutineScope.launch {
                            val scopeName = activeProject?.title ?: "Conversation"
                            snackbarHostState.showSnackbar("$scopeName messages cleared from Room database")
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                    modifier = Modifier.testTag("confirm_clear_chat_button")
                ) {
                    Text("Clear Chat", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showClearChatDialog = false },
                    modifier = Modifier.testTag("cancel_clear_chat_button")
                ) {
                    Text("Cancel")
                }
            },
            modifier = Modifier.testTag("clear_chat_dialog")
        )
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.widthIn(max = 320.dp),
                drawerContainerColor = MaterialTheme.colorScheme.surface
            ) {
                ChatSessionsDrawerContent(
                    recentSessions = recentSessions,
                    activeSessionId = activeSession?.id,
                    onSelectSession = { session ->
                        viewModel.selectSession(session)
                        coroutineScope.launch { drawerState.close() }
                    },
                    onNewChat = {
                        viewModel.startNewChatSession()
                        coroutineScope.launch { drawerState.close() }
                    },
                    onDeleteSession = { session ->
                        viewModel.deleteChatSession(session)
                    },
                    onCloseDrawer = {
                        coroutineScope.launch { drawerState.close() }
                    },
                    onOpenSettings = {
                        viewModel.setShowSettings(true)
                        coroutineScope.launch { drawerState.close() }
                    }
                )
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .auraBackground()
        ) {
            // Top Header
            ChatTopHeader(
                aiName = identity.name,
                archetype = identity.archetype.title,
                routingMode = routingMode,
                onSelectRoutingMode = { viewModel.setRoutingMode(it) },
                activeProjectName = activeProject?.title,
                projectShared = activeProject?.let { it.id in sharedProjectIds } ?: false,
                activeSessionTitle = activeSession?.title,
                hasMessages = messages.isNotEmpty() || currentStreamingText != null,
                onExitProjectContext = { viewModel.setActiveProject(null) },
                onClearChat = { showClearChatDialog = true },
                onOpenSettings = { viewModel.setShowSettings(true) },
                onOpenDrawer = {
                    coroutineScope.launch { drawerState.open() }
                }
            )

        // Chat Message List
        Box(modifier = Modifier.weight(1f)) {
            if (messages.isEmpty() && currentStreamingText == null) {
                EmptyChatWelcome(
                    aiName = identity.name,
                    archetype = identity.archetype.title,
                    values = identity.coreValues,
                    onSuggestionClick = { suggestion ->
                        viewModel.sendMessage(suggestion)
                    }
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    items(messages, key = { it.id }) { message ->
                        ChatMessageItem(
                            message = message,
                            proposedAction = taskActions.firstOrNull { it.id == message.proposedActionId },
                            onApproveAction = { viewModel.approveAction(it) },
                            onDismissAction = { viewModel.dismissAction(it) },
                            onSpeakText = { text ->
                                viewModel.speakReply(text)
                            },
                            aiName = identity.name,
                            projects = projectsForCards,
                            actionFailures = actionFailures
                        )
                    }

                    // Live Streaming bubble if actively generating
                    if (isGenerating && !currentStreamingText.isNullOrEmpty()) {
                        item(key = "streaming_bubble") {
                            StreamingAiBubble(
                                text = currentStreamingText ?: "",
                                modelUsed = streamingModelUsed ?: AiModelUsed.CLOUD,
                                aiName = identity.name
                            )
                        }
                    } else if (isGenerating) {
                        item(key = "generating_indicator") {
                            ThinkingRow(aiName = identity.name, modifier = Modifier.padding(vertical = 6.dp))
                        }
                    }
                }

                // Quick jump to bottom floating button when user has scrolled up
                androidx.compose.animation.AnimatedVisibility(
                    visible = !isAtBottom && totalItemCount > 2,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(bottom = 16.dp, end = 16.dp)
                ) {
                    SmallFloatingActionButton(
                        onClick = {
                            coroutineScope.launch {
                                listState.animateScrollToItem(lastItemIndex)
                            }
                        },
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.testTag("scroll_to_bottom_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = "Scroll to bottom"
                        )
                    }
                }
            }

            // Snackbar Host for action confirmations
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 8.dp)
                    .testTag("chat_snackbar_host")
            )
        }

        // Voice Orb & Input Deck
        Surface(
            color = Color.Transparent, // let the grain show through
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Compact composer: voice input stays on the trailing side of the text field.

                val voiceCaption = when (voiceState) {
                    VoiceState.LISTENING -> "Listening\u2026 your words appear below. Tap the stars to stop."
                    VoiceState.PROCESSING -> "Working on it\u2026 tap to stop."
                    VoiceState.SPEAKING -> "Reading aloud. Tap \u25A0 or start typing to stop."
                    VoiceState.IDLE -> null
                }
                if (voiceCaption != null) {
                    Text(
                        text = voiceCaption,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 4.dp).testTag("voice_caption")
                    )
                }

                // Input row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = {
                            // Typing takes over: stop listening (it would overwrite the text) or speaking.
                            if (voiceState != VoiceState.IDLE) viewModel.stopVoice()
                            inputText = it
                        },
                        placeholder = { Text("Message ${identity.name}") },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("chat_input_field"),
                        shape = RoundedCornerShape(14.dp),
                        minLines = 1,
                        maxLines = 4,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                            focusedContainerColor = MaterialTheme.colorScheme.surface,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                            cursorColor = MaterialTheme.colorScheme.primary
                        )
                    )

                    Spacer(Modifier.width(8.dp))

                    // While voice is active the button stays a stop control, even once words appear.
                    if (inputText.isNotBlank() && voiceState == VoiceState.IDLE) {
                        IconButton(
                            onClick = {
                                val text = inputText
                                inputText = ""
                                viewModel.sendMessage(text)
                                coroutineScope.launch {
                                    if (totalItemCount > 0) {
                                        listState.animateScrollToItem(lastItemIndex)
                                    }
                                }
                            },
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.primary)
                                .testTag("send_message_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send",
                                tint = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    } else {
                        IconButton(
                            onClick = {
                                when (voiceState) {
                                    VoiceState.IDLE -> {
                                        val hasPermission = ContextCompat.checkSelfPermission(
                                            context,
                                            Manifest.permission.RECORD_AUDIO
                                        ) == PackageManager.PERMISSION_GRANTED
                                        if (hasPermission) {
                                            startVoiceInput()
                                        } else {
                                            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                        }
                                    }
                                    VoiceState.LISTENING -> viewModel.voiceManager.stopListening()
                                    VoiceState.SPEAKING, VoiceState.PROCESSING -> viewModel.stopVoice()
                                }
                            },
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (voiceState == VoiceState.IDLE) MaterialTheme.colorScheme.surfaceVariant
                                    else MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                                )
                                .testTag("mic_input_button")
                        ) {
                            when (voiceState) {
                                VoiceState.IDLE -> Icon(
                                    imageVector = Icons.Default.Mic,
                                    contentDescription = "Voice input",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                VoiceState.LISTENING -> com.example.ui.components.StarListeningIndicator(
                                    modifier = Modifier.semantics { contentDescription = "Listening. Tap to stop." }
                                )
                                VoiceState.PROCESSING -> com.example.ui.components.StarThinkingIndicator(
                                    size = 22.dp,
                                    modifier = Modifier.semantics { contentDescription = "Working. Tap to stop." }
                                )
                                VoiceState.SPEAKING -> Icon(
                                    imageVector = Icons.Default.Stop,
                                    contentDescription = "Stop speaking",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
}

@Composable
fun ChatTopHeader(
    aiName: String,
    archetype: String,
    routingMode: HybridRoutingMode,
    onSelectRoutingMode: (HybridRoutingMode) -> Unit,
    activeProjectName: String?,
    /** Whether Claude can see the active project (the user's per-project switch). */
    projectShared: Boolean = false,
    activeSessionTitle: String? = null,
    hasMessages: Boolean,
    onExitProjectContext: () -> Unit,
    onClearChat: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDrawer: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f, fill = false)
            ) {
                IconButton(
                    onClick = onOpenDrawer,
                    modifier = Modifier
                        .size(38.dp)
                        .testTag("open_chat_drawer_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Menu,
                        contentDescription = "Open Chat History Drawer",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }

                Spacer(Modifier.width(4.dp))

                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = activeSessionTitle ?: aiName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        text = "$aiName · $archetype",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                // Model Selector Pill
                Box {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .clickable { menuExpanded = true }
                            .testTag("model_mode_selector_button")
                    ) {
                        Text(
                            text = routingMode.label,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }

                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        HybridRoutingMode.values().forEach { mode ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(mode.label, fontWeight = FontWeight.Bold)
                                        Text(mode.description, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                },
                                onClick = {
                                    onSelectRoutingMode(mode)
                                    menuExpanded = false
                                }
                            )
                        }
                    }
                }

                Spacer(Modifier.width(4.dp))

                // Settings Button
                IconButton(
                    onClick = onOpenSettings,
                    modifier = Modifier
                        .size(38.dp)
                        .testTag("open_settings_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Personalization Settings",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(19.dp)
                    )
                }

                // Clear Chat Button with clear action indication
                val clearIconTint = if (hasMessages) {
                    MaterialTheme.colorScheme.error.copy(alpha = 0.85f)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                }

                IconButton(
                    onClick = onClearChat,
                    modifier = Modifier
                        .size(38.dp)
                        .testTag("clear_chat_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteSweep,
                        contentDescription = "Clear Chat",
                        tint = clearIconTint,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        // Active project scope banner
        if (!activeProjectName.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Project: $activeProjectName",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = if (projectShared) "Shared: Claude can see its notes, milestones and to-dos"
                            else "Not shared: Claude can't see it (turn on in Projects)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                        modifier = Modifier.testTag("project_share_status")
                    )
                }
                IconButton(onClick = onExitProjectContext, modifier = Modifier.size(20.dp)) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Exit Project Context",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun ChatMessageItem(
    message: ChatMessageEntity,
    proposedAction: com.example.data.model.TaskActionEntity?,
    onApproveAction: (com.example.data.model.TaskActionEntity) -> Unit,
    onDismissAction: (com.example.data.model.TaskActionEntity) -> Unit,
    onSpeakText: (String) -> Unit,
    aiName: String = "Aura",
    projects: List<com.example.data.model.ProjectEntity> = emptyList(),
    actionFailures: Map<Long, String> = emptyMap()
) {
    val isUser = message.sender == Sender.USER

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        if (isUser) {
            Column(
                modifier = Modifier
                    .widthIn(max = 300.dp)
                    .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 4.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.End
            ) {
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = message.getFormattedTime(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp,
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .testTag("message_timestamp_${message.id}")
                )
            }
        } else {
            // AI reply: no bubble, full width, with a tag saying where it came from.
            val presentation = remember(message.text, message.modelUsed) {
                MessageNotice.present(message.text, message.modelUsed)
            }
            AiReplyLabel(aiName = aiName, modelUsed = message.modelUsed, reasoningExplanation = message.reasoningExplanation)
            Spacer(Modifier.height(8.dp))
            presentation.notice?.let { notice ->
                if (presentation.noticeAfterBody) {
                    // Incomplete answer: the text that arrived first, then the warning.
                    AiReplyText(presentation.body)
                    Spacer(Modifier.height(10.dp))
                    NoticeBox(notice)
                } else {
                    NoticeBox(notice)
                    if (presentation.body.isNotBlank()) {
                        Spacer(Modifier.height(10.dp))
                        AiReplyText(presentation.body)
                    }
                }
            } ?: AiReplyText(presentation.body)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { onSpeakText(MarkdownRenderer.plainText(presentation.body.ifBlank { message.text })) },
                    modifier = Modifier.offset(x = (-12).dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = "Read Aloud",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Text(
                    text = message.getFormattedTime(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp,
                    modifier = Modifier.testTag("message_timestamp_${message.id}")
                )
            }
        }

        // Proposed Action Card if AI proposed one during this turn
        if (proposedAction != null) {
            Spacer(Modifier.height(8.dp))
            ActionProposalCard(
                action = proposedAction,
                onApprove = onApproveAction,
                onDismiss = onDismissAction,
                proposedByClaude = message.modelUsed == AiModelUsed.CLOUD,
                projects = projects,
                failureReason = actionFailures[proposedAction.id]
            )
        }
    }
}

/** "STARAI  [Claude]" — the assistant's name and where this reply came from. */
@Composable
private fun AiReplyLabel(aiName: String, modelUsed: AiModelUsed?, reasoningExplanation: String? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = aiName.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = 0.8.sp
        )
        if (modelUsed != null) {
            ModelBadge(modelUsed = modelUsed, reasoningExplanation = reasoningExplanation)
        }
    }
}

@Composable
private fun AiReplyText(text: String, modifier: Modifier = Modifier) {
    MarkdownText(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.secondary,
        codeBackground = MaterialTheme.colorScheme.surfaceVariant
    )
}

@Composable
fun StreamingAiBubble(text: String, modelUsed: AiModelUsed, aiName: String = "Aura") {
    val infiniteTransition = rememberInfiniteTransition(label = "cursor_blink")
    val cursorAlpha by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 450),
            repeatMode = RepeatMode.Reverse
        ),
        label = "cursor_alpha"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("streaming_ai_bubble"),
        horizontalAlignment = Alignment.Start
    ) {
        AiReplyLabel(aiName = aiName, modelUsed = modelUsed)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            AiReplyText(text, modifier = Modifier.weight(1f, fill = false))
            Box(
                modifier = Modifier
                    .padding(start = 3.dp, bottom = 4.dp)
                    .size(width = 2.dp, height = 16.dp)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = cursorAlpha))
            )
        }
    }
}

@Composable
fun GeneratingIndicator(aiName: String = "Aura", modelUsed: AiModelUsed? = null) {
    AiTypingIndicator(
        aiName = aiName,
        statusText = "is thinking...",
        modelUsed = modelUsed
    )
}

@Composable
fun EmptyChatWelcome(
    aiName: String,
    archetype: String,
    values: String,
    onSuggestionClick: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Welcome to $aiName",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Hybrid Personal AI Partner • $archetype",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary
        )

        Spacer(Modifier.height(16.dp))

        Text(
            text = "Autonomous execution bound by your approval. Uses built-in offline replies for simple commands, and Claude for real conversations when cloud chat is on.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )

        Spacer(Modifier.height(24.dp))

        Text(
            text = "Suggested Prompts",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(Modifier.height(10.dp))

        val suggestions = listOf(
            "Remember that I prefer Kotlin and concise code",
            "Create project Mobile Redesign",
            "What do you remember about me?",
            "Add task Schedule product review meeting",
            "Show my project library status"
        )

        suggestions.forEach { s ->
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clickable { onSuggestionClick(s) }
            ) {
                Text(
                    text = "✦  $s",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
    }
}
