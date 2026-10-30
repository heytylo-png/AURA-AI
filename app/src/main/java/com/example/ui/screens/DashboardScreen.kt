package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AssignmentTurnedIn
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.ai.HybridRoutingMode
import com.example.data.model.ActionStatus
import com.example.data.model.ProjectEntity
import com.example.data.model.ProjectStatus
import com.example.data.model.TaskActionEntity
import com.example.ui.components.ActionProposalCard
import com.example.ui.components.VoiceOrb
import com.example.viewmodel.AuraViewModel
import com.example.voice.VoiceState

/**
 * Primary Dashboard UI built with Material 3.
 * Serves as the central command interface for:
 * 1. Clean interaction with the AI (quick prompts, speech input, persona status)
 * 2. Displaying project cards with progress and context switching
 * 3. Viewing current task and action approval status
 */
@Composable
fun DashboardScreen(
    viewModel: AuraViewModel,
    onNavigateToChat: () -> Unit,
    onNavigateToProjects: () -> Unit,
    onNavigateToTasks: () -> Unit,
    onNavigateToMemory: () -> Unit,
    onNavigateToIdentity: () -> Unit
) {
    val context = LocalContext.current
    val identity by viewModel.identity.collectAsState()
    val userSettings by viewModel.userSettings.collectAsState()
    val routingMode by viewModel.routingMode.collectAsState()
    val voiceState by viewModel.voiceState.collectAsState()
    val projects by viewModel.allProjects.collectAsState()
    val taskActions by viewModel.allTaskActions.collectAsState()
    val pendingCount by viewModel.pendingApprovalCount.collectAsState()
    val memories by viewModel.allMemories.collectAsState()

    var quickPromptText by remember { mutableStateOf("") }
    var showCreateProjectDialog by remember { mutableStateOf(false) }
    var routingMenuExpanded by remember { mutableStateOf(false) }

    val pendingActions = remember(taskActions) {
        taskActions.filter { it.status == ActionStatus.PROPOSED }
    }
    val executedActions = remember(taskActions) {
        taskActions.filter { it.status == ActionStatus.APPROVED_EXECUTED }
    }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.voiceManager.startListening { spoken ->
                quickPromptText = spoken
                viewModel.sendMessage(spoken)
                onNavigateToChat()
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .testTag("dashboard_scroll_list"),
            contentPadding = PaddingValues(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Top Header with User Greeting and AI Status
            item {
                DashboardHeader(
                    userName = userSettings.userName,
                    primaryInterest = userSettings.primaryInterest,
                    aiName = identity.name,
                    archetype = identity.archetype.title,
                    routingMode = routingMode,
                    routingMenuExpanded = routingMenuExpanded,
                    onToggleRoutingMenu = { routingMenuExpanded = it },
                    onSelectRoutingMode = {
                        viewModel.setRoutingMode(it)
                        routingMenuExpanded = false
                    },
                    onOpenSettings = { viewModel.setShowSettings(true) },
                    onOpenIdentity = onNavigateToIdentity
                )
            }

            // 2. AI Quick Interaction Deck
            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    AiInteractionDeck(
                        aiName = identity.name,
                        voiceState = voiceState,
                        inputText = quickPromptText,
                        onInputTextChange = { quickPromptText = it },
                        onSendMessage = { prompt ->
                            if (prompt.isNotBlank()) {
                                viewModel.sendMessage(prompt)
                                quickPromptText = ""
                                onNavigateToChat()
                            }
                        },
                        onVoiceClick = {
                            when (voiceState) {
                                VoiceState.IDLE -> {
                                    val hasPermission = ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.RECORD_AUDIO
                                    ) == PackageManager.PERMISSION_GRANTED
                                    if (hasPermission) {
                                        viewModel.voiceManager.startListening { spoken ->
                                            viewModel.sendMessage(spoken)
                                            onNavigateToChat()
                                        }
                                    } else {
                                        audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    }
                                }
                                VoiceState.LISTENING -> viewModel.voiceManager.stopListening()
                                VoiceState.SPEAKING -> viewModel.voiceManager.stopSpeaking()
                                VoiceState.PROCESSING -> {}
                            }
                        },
                        onQuickSuggestionClick = { prompt ->
                            viewModel.sendMessage(prompt)
                            onNavigateToChat()
                        }
                    )
                }
            }

            // 3. Quick System Metrics Strip
            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    SystemMetricsStrip(
                        activeProjectsCount = projects.size,
                        pendingApprovalsCount = pendingCount,
                        storedMemoriesCount = memories.size,
                        routingMode = routingMode.label,
                        onProjectsClick = onNavigateToProjects,
                        onTasksClick = onNavigateToTasks,
                        onMemoriesClick = onNavigateToMemory
                    )
                }
            }

            // 4. Pending Approval Alert (if any)
            if (pendingActions.isNotEmpty()) {
                item {
                    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                        PendingApprovalsHighlight(
                            pendingActions = pendingActions,
                            onApprove = { viewModel.approveAction(it) },
                            onDismiss = { viewModel.dismissAction(it) },
                            onViewAll = onNavigateToTasks
                        )
                    }
                }
            }

            // 5. Project Cards Section
            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Folder,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "Active Projects",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                onClick = { showCreateProjectDialog = true },
                                modifier = Modifier.testTag("dashboard_new_project_btn")
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("New", fontSize = 12.sp)
                            }

                            TextButton(onClick = onNavigateToProjects) {
                                Text("See all (${projects.size})", fontSize = 12.sp)
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    if (projects.isEmpty()) {
                        EmptyProjectsCard(onCreateProject = { showCreateProjectDialog = true })
                    } else {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            contentPadding = PaddingValues(end = 8.dp)
                        ) {
                            items(projects.take(5), key = { it.id }) { project ->
                                DashboardProjectCard(
                                    project = project,
                                    viewModel = viewModel,
                                    onChatInContext = {
                                        viewModel.setActiveProject(project)
                                        onNavigateToChat()
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // 6. Current Task & Execution Status Section
            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.AssignmentTurnedIn,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "Current Task Status",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        TextButton(onClick = onNavigateToTasks) {
                            Text("Task Log (${taskActions.size})", fontSize = 12.sp)
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    TaskStatusSummaryCard(
                        totalCount = taskActions.size,
                        pendingCount = pendingCount,
                        executedCount = executedActions.size,
                        recentActions = taskActions.take(4),
                        onViewTasks = onNavigateToTasks
                    )
                }
            }
        }
    }

    if (showCreateProjectDialog) {
        CreateProjectDialog(
            onDismiss = { showCreateProjectDialog = false },
            onConfirm = { title, desc, cat ->
                viewModel.createProject(title, desc, cat)
                showCreateProjectDialog = false
            }
        )
    }
}

/**
 * Top header containing personalized user greeting and AI model status.
 */
@Composable
private fun DashboardHeader(
    userName: String,
    primaryInterest: String,
    aiName: String,
    archetype: String,
    routingMode: HybridRoutingMode,
    routingMenuExpanded: Boolean,
    onToggleRoutingMenu: (Boolean) -> Unit,
    onSelectRoutingMode: (HybridRoutingMode) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenIdentity: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // User & Persona Info
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (userName.isNotBlank()) "Welcome back, $userName" else "Welcome to $aiName",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 2.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF00E676))
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = if (primaryInterest.isNotBlank()) "$aiName • $primaryInterest" else "$aiName • $archetype",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Controls: Routing Model pill, Identity & Settings
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Routing selector
                    Box {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier
                                .clickable { onToggleRoutingMenu(true) }
                                .testTag("dashboard_model_selector")
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
                            expanded = routingMenuExpanded,
                            onDismissRequest = { onToggleRoutingMenu(false) }
                        ) {
                            HybridRoutingMode.values().forEach { mode ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(mode.label, fontWeight = FontWeight.Bold)
                                            Text(
                                                mode.description,
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    },
                                    onClick = { onSelectRoutingMode(mode) }
                                )
                            }
                        }
                    }

                    Spacer(Modifier.width(4.dp))

                    IconButton(
                        onClick = onOpenIdentity,
                        modifier = Modifier.size(38.dp).testTag("dashboard_identity_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = "AI Persona & Voice",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(19.dp)
                        )
                    }

                    IconButton(
                        onClick = onOpenSettings,
                        modifier = Modifier.size(38.dp).testTag("dashboard_settings_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * AI interaction deck with input bar, voice orb, and quick suggestions.
 */
@Composable
private fun AiInteractionDeck(
    aiName: String,
    voiceState: VoiceState,
    inputText: String,
    onInputTextChange: (String) -> Unit,
    onSendMessage: (String) -> Unit,
    onVoiceClick: () -> Unit,
    onQuickSuggestionClick: (String) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("ai_interaction_deck_card"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Interact with $aiName",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Text(
                    text = when (voiceState) {
                        VoiceState.IDLE -> "Hybrid Engine Ready"
                        VoiceState.LISTENING -> "Listening..."
                        VoiceState.PROCESSING -> "Processing..."
                        VoiceState.SPEAKING -> "Speaking..."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(Modifier.height(12.dp))

            // Input Bar with Voice Orb
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = onInputTextChange,
                    placeholder = {
                        Text(
                            "Ask $aiName, plan an initiative, or set a rule...",
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("dashboard_prompt_input"),
                    shape = RoundedCornerShape(24.dp),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface
                    )
                )

                Spacer(Modifier.width(8.dp))

                if (inputText.isNotBlank()) {
                    IconButton(
                        onClick = { onSendMessage(inputText) },
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                            .testTag("dashboard_send_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send prompt",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                } else {
                    VoiceOrb(
                        voiceState = voiceState,
                        size = 44.dp,
                        onClick = onVoiceClick
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // Quick suggestion chips
            Text(
                text = "Quick Actions",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(6.dp))

            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val suggestions = listOf(
                    "Plan next milestone",
                    "What do you remember about me?",
                    "Analyze my active projects",
                    "Add task: Review sprint objectives",
                    "Summarize pending actions"
                )

                items(suggestions) { suggestion ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
                        modifier = Modifier.clickable { onQuickSuggestionClick(suggestion) }
                    ) {
                        Text(
                            text = "✦ $suggestion",
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Metric stats strip for active counts.
 */
@Composable
private fun SystemMetricsStrip(
    activeProjectsCount: Int,
    pendingApprovalsCount: Int,
    storedMemoriesCount: Int,
    routingMode: String,
    onProjectsClick: () -> Unit,
    onTasksClick: () -> Unit,
    onMemoriesClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        MetricCard(
            modifier = Modifier.weight(1f),
            label = "Projects",
            value = "$activeProjectsCount",
            icon = Icons.Default.Folder,
            accentColor = MaterialTheme.colorScheme.primary,
            onClick = onProjectsClick
        )

        MetricCard(
            modifier = Modifier.weight(1f),
            label = "Approvals",
            value = "$pendingApprovalsCount",
            icon = Icons.Default.HourglassEmpty,
            accentColor = if (pendingApprovalsCount > 0) Color(0xFFFFB74D) else MaterialTheme.colorScheme.secondary,
            onClick = onTasksClick
        )

        MetricCard(
            modifier = Modifier.weight(1f),
            label = "Memories",
            value = "$storedMemoriesCount",
            icon = Icons.Default.Psychology,
            accentColor = Color(0xFF00E676),
            onClick = onMemoriesClick
        )
    }
}

@Composable
private fun MetricCard(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accentColor: Color,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier.clickable { onClick() },
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.25f))
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(14.dp)
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

/**
 * Prominent banner showing tasks awaiting user authorization.
 */
@Composable
private fun PendingApprovalsHighlight(
    pendingActions: List<TaskActionEntity>,
    onApprove: (TaskActionEntity) -> Unit,
    onDismiss: (TaskActionEntity) -> Unit,
    onViewAll: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("dashboard_pending_approvals_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.15f)
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.HourglassEmpty,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "${pendingActions.size} Action${if (pendingActions.size > 1) "s" else ""} Awaiting Approval",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                TextButton(onClick = onViewAll) {
                    Text("View all", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                }
            }

            Spacer(Modifier.height(8.dp))

            // Show first pending action for immediate approval
            val firstAction = pendingActions.firstOrNull()
            if (firstAction != null) {
                ActionProposalCard(
                    action = firstAction,
                    onApprove = onApprove,
                    onDismiss = onDismiss
                )
            }
        }
    }
}

/**
 * Horizontal project card for the dashboard carousel.
 */
@Composable
private fun DashboardProjectCard(
    project: ProjectEntity,
    viewModel: AuraViewModel,
    onChatInContext: () -> Unit
) {
    val milestones by viewModel.getMilestonesFlow(project.id).collectAsState(initial = emptyList())
    val completedCount = milestones.count { it.isCompleted }
    val progress = if (milestones.isNotEmpty()) completedCount.toFloat() / milestones.size else 0f

    Card(
        modifier = Modifier
            .width(260.dp)
            .testTag("dashboard_project_card_${project.id}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = project.category,
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }

                Text(
                    text = project.status.label,
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(Modifier.height(8.dp))

            Text(
                text = project.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (project.description.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = project.description,
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.height(10.dp))

            // Progress bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Milestones ($completedCount/${milestones.size})",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "${(progress * 100).toInt()}%",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(Modifier.height(4.dp))

            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surface
            )

            Spacer(Modifier.height(10.dp))

            OutlinedButton(
                onClick = onChatInContext,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(34.dp),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Chat,
                    contentDescription = null,
                    modifier = Modifier.size(13.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(6.dp))
                Text("Chat in Context", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/**
 * Card shown when no projects exist yet.
 */
@Composable
private fun EmptyProjectsCard(onCreateProject: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "No initiatives yet",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Organize goals, milestones, and artifacts into focused projects.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = onCreateProject,
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("Create First Project", fontSize = 12.sp)
            }
        }
    }
}

/**
 * Task status summary card with breakdown and recent timeline items.
 */
@Composable
private fun TaskStatusSummaryCard(
    totalCount: Int,
    pendingCount: Int,
    executedCount: Int,
    recentActions: List<TaskActionEntity>,
    onViewTasks: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("dashboard_task_summary_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Stats pills row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                StatusPill(
                    label = "Awaiting Approval",
                    count = pendingCount,
                    color = if (pendingCount > 0) Color(0xFFFFB74D) else MaterialTheme.colorScheme.onSurfaceVariant
                )
                StatusPill(
                    label = "Executed by AI",
                    count = executedCount,
                    color = Color(0xFF00E676)
                )
                StatusPill(
                    label = "Total Logged",
                    count = totalCount,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            if (recentActions.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "Recent Autonomous Actions",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    recentActions.forEach { action ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                val statusIcon = when (action.status) {
                                    ActionStatus.APPROVED_EXECUTED -> Icons.Default.CheckCircle
                                    ActionStatus.PROPOSED -> Icons.Default.HourglassEmpty
                                    ActionStatus.DISMISSED -> Icons.Default.AssignmentTurnedIn
                                }
                                val statusColor = when (action.status) {
                                    ActionStatus.APPROVED_EXECUTED -> Color(0xFF00E676)
                                    ActionStatus.PROPOSED -> Color(0xFFFFB74D)
                                    ActionStatus.DISMISSED -> MaterialTheme.colorScheme.outline
                                }

                                Icon(
                                    imageVector = statusIcon,
                                    contentDescription = null,
                                    tint = statusColor,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = action.title,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = action.actionType.title,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.outline,
                                modifier = Modifier
                                    .size(14.dp)
                                    .clickable { onViewTasks() }
                            )
                        }
                    }
                }
            } else {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "No AI actions proposed yet. As you converse with Aura, action proposals with human-in-the-loop checkpoints will appear here.",
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun StatusPill(label: String, count: Int, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "$count",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = color
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
