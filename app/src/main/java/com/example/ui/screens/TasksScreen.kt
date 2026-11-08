package com.example.ui.screens

import com.example.ui.theme.auraBackground
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AssignmentTurnedIn
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.ActionStatus
import com.example.ui.components.ActionProposalCard
import com.example.viewmodel.AuraViewModel
import kotlinx.coroutines.launch
import com.example.ui.components.todoItems
import com.example.ui.components.TodoEditorDialog
import com.example.data.model.TodoRules
import com.example.data.model.TodoEntity
import androidx.compose.material.icons.filled.Add
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.FloatingActionButton

@Composable
fun TasksScreen(viewModel: AuraViewModel) {
    val allActions by viewModel.allTaskActions.collectAsState()
    val pendingCount by viewModel.pendingApprovalCount.collectAsState()
    val todos by viewModel.visibleTodos.collectAsState()
    val deletedTodos by viewModel.deletedTodos.collectAsState()
    val projects by viewModel.allProjects.collectAsState()
    val openCount = todos.count { !it.done }

    var statusFilter by remember { mutableStateOf<ActionStatus?>(null) }
    var showDone by remember { mutableStateOf(false) }
    var showDeleted by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<TodoEntity?>(null) }
    var adding by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val today = remember { TodoRules.today() }

    val filteredActions = remember(allActions, statusFilter) {
        if (statusFilter == null) allActions else allActions.filter { it.status == statusFilter }
    }

    val pendingActions = remember(allActions) {
        allActions.filter { it.status == ActionStatus.PROPOSED }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .auraBackground()
    ) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Header
        Surface(
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Tasks",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "$openCount open to-do${if (openCount == 1) "" else "s"} \u00b7 $pendingCount waiting for approval",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    imageVector = Icons.Default.AssignmentTurnedIn,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            todoItems(
                visible = todos,
                deleted = deletedTodos,
                projects = projects,
                today = today,
                showDone = showDone,
                onShowDone = { showDone = it },
                showDeleted = showDeleted,
                onShowDeleted = { showDeleted = it },
                onToggle = { viewModel.toggleTodoDone(it) },
                onEdit = { editing = it },
                onDelete = { todo ->
                    viewModel.deleteTodo(todo)
                    scope.launch {
                        val result = snackbar.showSnackbar("Moved \"${todo.title.take(40)}\" to Recently deleted", actionLabel = "Undo", duration = SnackbarDuration.Short)
                        if (result == SnackbarResult.ActionPerformed) viewModel.restoreTodo(todo)
                    }
                },
                onRestore = { viewModel.restoreTodo(it) },
                onDeleteForever = { viewModel.deleteTodoForever(it) }
            )

            item(key = "approvals-header") {
                Column(modifier = Modifier.padding(top = 16.dp)) {
                    Text("Approvals", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        "Changes suggested in chat. Nothing happens until you approve.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                        // Status Filter Chips
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            item {
                                FilterChip(
                                    label = "All (${allActions.size})",
                                    isSelected = statusFilter == null,
                                    onClick = { statusFilter = null }
                                )
                            }
                            item {
                                FilterChip(
                                    label = "Pending (${pendingActions.size})",
                                    isSelected = statusFilter == ActionStatus.PROPOSED,
                                    onClick = { statusFilter = ActionStatus.PROPOSED }
                                )
                            }
                            item {
                                FilterChip(
                                    label = "Completed (${allActions.count { it.status == ActionStatus.APPROVED_EXECUTED }})",
                                    isSelected = statusFilter == ActionStatus.APPROVED_EXECUTED,
                                    onClick = { statusFilter = ActionStatus.APPROVED_EXECUTED }
                                )
                            }
                            item {
                                FilterChip(
                                    label = "Failed (${allActions.count { it.status == ActionStatus.FAILED }})",
                                    isSelected = statusFilter == ActionStatus.FAILED,
                                    onClick = { statusFilter = ActionStatus.FAILED }
                                )
                            }
                            item {
                                FilterChip(
                                    label = "Dismissed (${allActions.count { it.status == ActionStatus.DISMISSED }})",
                                    isSelected = statusFilter == ActionStatus.DISMISSED,
                                    onClick = { statusFilter = ActionStatus.DISMISSED }
                                )
                            }
                        }
                }
            }

            if (filteredActions.isEmpty()) {
                item(key = "approvals-empty") {
                    Text(
                        text = if (statusFilter == ActionStatus.PROPOSED) "Nothing waiting for approval." else "No approvals in this list.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                if (statusFilter == null && pendingActions.isNotEmpty()) {
                    item(key = "approvals-pending-label") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.HourglassEmpty,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.size(6.dp))
                            Text(
                                text = "Awaiting your approval",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
                items(filteredActions, key = { "action-${it.id}" }) { action ->
                    ActionProposalCard(
                        action = action,
                        onApprove = { viewModel.approveAction(it) },
                        onDismiss = { viewModel.dismissAction(it) },
                        projects = projects
                    )
                }
            }
        }
    }

        FloatingActionButton(
            onClick = { adding = true },
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).testTag("add_todo_fab")
        ) {
            Icon(Icons.Default.Add, contentDescription = "Add to-do")
        }
        SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 88.dp))
    }

    if (adding) {
        TodoEditorDialog(
            initial = null,
            projects = projects,
            onSave = { t, n, d, p -> viewModel.addTodo(t, n, d, p) },
            onDismiss = { adding = false }
        )
    }
    editing?.let { todo ->
        TodoEditorDialog(
            initial = todo,
            projects = projects,
            onSave = { t, n, d, p -> viewModel.editTodo(todo, t, n, d, p) },
            onDismiss = { editing = null }
        )
    }
}
