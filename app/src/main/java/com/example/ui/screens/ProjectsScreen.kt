package com.example.ui.screens

import com.example.ui.theme.auraBackground
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import com.example.data.model.ProjectArtifactEntity
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.MilestoneEntity
import com.example.data.model.ProjectEntity
import com.example.data.model.ProjectStatus
import com.example.viewmodel.AuraViewModel
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.heightIn

@Composable
fun ProjectsScreen(
    viewModel: AuraViewModel,
    onNavigateToChat: () -> Unit
) {
    val projects by viewModel.allProjects.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .auraBackground()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 4.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Project Library",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "${projects.size} active initiatives organized with milestones",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            // Project cards list
            if (projects.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "Your Project Library is empty",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Tap + to start a project.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    items(projects, key = { it.id }) { project ->
                        ProjectCard(
                            project = project,
                            viewModel = viewModel,
                            onChatWithProject = {
                                viewModel.setActiveProject(project)
                                onNavigateToChat()
                            },
                            onDelete = { viewModel.deleteProject(project) }
                        )
                    }
                }
            }
        }

        // FAB
        FloatingActionButton(
            onClick = { showAddDialog = true },
            containerColor = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp)
                .testTag("add_project_fab")
        ) {
            Icon(Icons.Default.Add, contentDescription = "New Project", tint = MaterialTheme.colorScheme.onPrimary)
        }
    }

    if (showAddDialog) {
        CreateProjectDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { title, desc, cat ->
                viewModel.createProject(title, desc, cat)
                showAddDialog = false
            }
        )
    }
}

/** What deleting a project removes, said before it happens (it can't be undone). */
internal fun deleteProjectWarning(milestones: Int, notes: Int): String {
    fun count(n: Int, word: String) = "$n $word${if (n == 1) "" else "s"}"
    val contents = listOfNotNull(
        count(milestones, "milestone").takeIf { milestones > 0 },
        count(notes, "note").takeIf { notes > 0 },
        "its chat with Aura"
    )
    return "This permanently deletes the project, ${contents.joinToString(", ")}. It can't be undone. " +
        "Its to-dos are kept, without a project."
}

@Composable
fun ProjectCard(
    project: ProjectEntity,
    viewModel: AuraViewModel,
    onChatWithProject: () -> Unit,
    onDelete: () -> Unit
) {
    val milestones by viewModel.getMilestonesFlow(project.id).collectAsState(initial = emptyList())
    val artifacts by viewModel.getArtifactsFlow(project.id).collectAsState(initial = emptyList())
    val completedCount = milestones.count { it.isCompleted }
    val progress = if (milestones.isNotEmpty()) completedCount.toFloat() / milestones.size else 0f

    var showAddMilestoneDialog by remember { mutableStateOf(false) }
    var showAddTodoDialog by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val allTodos by viewModel.visibleTodos.collectAsState()
    val allProjects by viewModel.allProjects.collectAsState()

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete \"${project.title}\"?") },
            text = { Text(deleteProjectWarning(milestones.size, artifacts.size)) },
            confirmButton = {
                TextButton(
                    onClick = { confirmDelete = false; onDelete() },
                    modifier = Modifier.testTag("project_delete_confirm")
                ) { Text("Delete project", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep") } },
            modifier = Modifier.testTag("project_delete_dialog")
        )
    }
    val projectTodos = remember(allTodos, project.id) {
        allTodos.filter { it.projectId == project.id && !it.done }.sortedWith(com.example.data.model.TodoRules.openOrder)
    }
    var showAddArtifactDialog by remember { mutableStateOf(false) }
    var editingNote by remember { mutableStateOf<ProjectArtifactEntity?>(null) }
    val sharedProjectIds by viewModel.sharedProjectIds.collectAsState()
    val cloudChatEnabled by viewModel.cloudChatEnabled.collectAsState()
    val projectShared = project.id in sharedProjectIds
    var statusMenuExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("project_card_${project.id}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Category & Status
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = project.category,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Status picker pill
                    Box {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.clickable { statusMenuExpanded = true }
                        ) {
                            Text(
                                text = project.status.label,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }

                        DropdownMenu(
                            expanded = statusMenuExpanded,
                            onDismissRequest = { statusMenuExpanded = false }
                        ) {
                            ProjectStatus.values().forEach { st ->
                                DropdownMenuItem(
                                    text = { Text(st.label) },
                                    onClick = {
                                        viewModel.updateProjectStatus(project, st)
                                        statusMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    Spacer(Modifier.width(4.dp))

                    IconButton(
                        onClick = { confirmDelete = true },
                        modifier = Modifier.size(28.dp).testTag("project_delete_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete Project",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // Title & Description
            Text(
                text = project.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            if (project.description.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = project.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(12.dp))

            // Progress bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Milestones ($completedCount/${milestones.size})",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "${(progress * 100).toInt()}%",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(Modifier.height(4.dp))

            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surface
            )

            // Milestones list
            if (milestones.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    milestones.forEach { m ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.toggleMilestoneCompleted(m) }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (m.isCompleted) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                contentDescription = if (m.isCompleted) "Completed" else "Incomplete",
                                tint = if (m.isCompleted) Color(0xFF00E676) else MaterialTheme.colorScheme.outline,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = m.title,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (m.isCompleted)
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                else
                                    MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }

            // Artifacts & Documents list
            if (artifacts.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "Notes & documents (${artifacts.size}) \u00B7 tap to open",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(4.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    artifacts.forEach { art ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { editingNote = art }
                                .testTag("project_note_${art.id}")
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Icon(
                                    imageVector = Icons.Default.Description,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Column {
                                    Text(
                                        text = art.title,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    if (art.content.isNotBlank()) {
                                        Text(
                                            text = art.content,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2
                                        )
                                    }
                                }
                            }
                            IconButton(onClick = { viewModel.deleteArtifact(art) }, modifier = Modifier.size(24.dp)) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Delete",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(13.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Sharing with Claude: off for every project until the user turns it on.
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Text("Share with Claude in this project's chat", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        text = when {
                            !cloudChatEnabled -> "Cloud chat is off in Settings."
                            projectShared -> "Claude sees this project's description, milestones, open to-dos and notes (newest first, up to about 18,000 characters) when you chat here."
                            else -> "Off: Claude can't see this project. Turning it off also stops earlier chat messages (which may mention it) from being sent."
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                androidx.compose.material3.Switch(
                    checked = projectShared,
                    onCheckedChange = { viewModel.setProjectShared(project.id, it) },
                    enabled = cloudChatEnabled,
                    colors = com.example.ui.theme.auraSwitchColors(),
                    modifier = Modifier.testTag("project_share_${project.id}")
                )
            }

            // Open to-dos for this project (managed in the Tasks tab).
            if (projectTodos.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "To-dos (${projectTodos.size} open)",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                projectTodos.take(5).forEach { todo ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.toggleTodoDone(todo) }
                            .padding(vertical = 4.dp)
                            .testTag("project_todo_${todo.id}")
                    ) {
                        Icon(Icons.Default.RadioButtonUnchecked, contentDescription = "Mark done", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(8.dp))
                        Text(todo.title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                        todo.dueEpochDay?.let {
                            Text(
                                com.example.ui.components.dueLabel(it, com.example.data.model.TodoRules.today()),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (com.example.data.model.TodoRules.isOverdue(todo, com.example.data.model.TodoRules.today())) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                if (projectTodos.size > 5) {
                    Text("+${projectTodos.size - 5} more in Tasks", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Spacer(Modifier.height(12.dp))

            // Action row: add buttons on one line, the chat button full width below, so nothing is
            // squeezed on narrow phones (the chat label used to wrap one letter per line).
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { showAddMilestoneDialog = true }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp)) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(2.dp))
                    Text("Milestone", fontSize = 12.sp, maxLines = 1)
                }
                TextButton(
                    onClick = { showAddTodoDialog = true },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp),
                    modifier = Modifier.testTag("project_add_todo_${project.id}")
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(2.dp))
                    Text("To-do", fontSize = 12.sp, maxLines = 1)
                }
                TextButton(
                    onClick = { showAddArtifactDialog = true },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp),
                    modifier = Modifier.testTag("project_add_note_${project.id}")
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(2.dp))
                    Text("Note", fontSize = 12.sp, maxLines = 1)
                }
            }
            Spacer(Modifier.height(6.dp))
            OutlinedButton(
                onClick = onChatWithProject,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth().testTag("chat_project_btn_${project.id}")
            ) {
                Icon(
                    imageVector = Icons.Default.Chat,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (projectShared) "Chat about this project" else "Chat in this project",
                    fontSize = 13.sp, maxLines = 1, color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }

    if (showAddTodoDialog) {
        com.example.ui.components.TodoEditorDialog(
            initial = null,
            projects = allProjects,
            presetProjectId = project.id,
            onSave = { t, n, d, p -> viewModel.addTodo(t, n, d, p) },
            onDismiss = { showAddTodoDialog = false }
        )
    }

    if (showAddMilestoneDialog) {
        AddMilestoneDialog(
            onDismiss = { showAddMilestoneDialog = false },
            onConfirm = { title ->
                viewModel.addMilestone(project.id, title)
                showAddMilestoneDialog = false
            }
        )
    }

    if (showAddArtifactDialog) {
        AddArtifactDialog(
            viewModel = viewModel,
            onDismiss = { showAddArtifactDialog = false },
            onConfirm = { title, content, type ->
                viewModel.addArtifact(project.id, title, content, type)
                showAddArtifactDialog = false
            }
        )
    }

    editingNote?.let { note ->
        AddArtifactDialog(
            viewModel = viewModel,
            initial = note,
            onDismiss = { editingNote = null },
            onConfirm = { title, content, _ ->
                viewModel.updateArtifact(note, title, content)
                editingNote = null
            },
            onDelete = {
                viewModel.deleteArtifact(note)
                editingNote = null
            }
        )
    }
}

/** Add or edit a project note. Text files can be imported into it. */
@Composable
fun AddArtifactDialog(
    viewModel: AuraViewModel,
    onDismiss: () -> Unit,
    onConfirm: (String, String, String) -> Unit,
    initial: ProjectArtifactEntity? = null,
    onDelete: (() -> Unit)? = null
) {
    var title by remember { mutableStateOf(initial?.title ?: "") }
    var content by remember { mutableStateOf(initial?.content ?: "") }
    var type by remember { mutableStateOf(initial?.type ?: "NOTE") }
    var importError by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val pickFile = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) scope.launch {
            importing = true
            importError = null
            val result = viewModel.readTextFile(uri)
            importing = false
            result
                .onSuccess { (name, text) ->
                    if (title.isBlank()) title = name
                    content = if (content.isBlank()) text else content + "\n\n" + text
                    type = "FILE"
                    importError = null
                }
                .onFailure { importError = it.message ?: "Couldn't read that file." }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "New note" else "Note", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    modifier = Modifier.fillMaxWidth().testTag("note_title_field"),
                    singleLine = true
                )
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("Note") },
                    modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp).testTag("note_content_field"),
                    minLines = 4
                )
                TextButton(onClick = { pickFile.launch(arrayOf("text/*", "application/pdf", "application/json", "application/xml", "application/octet-stream")) }) {
                    Text("Import a file (PDF, .txt, .md, .csv, code)")
                }
                if (importing) Text("Reading the file on this phone\u2026", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                importError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                if (onDelete != null) {
                    if (!confirmDelete) {
                        TextButton(onClick = { confirmDelete = true }) { Text("Delete note", color = MaterialTheme.colorScheme.error) }
                    } else {
                        TextButton(onClick = onDelete, modifier = Modifier.testTag("note_delete_confirm")) {
                            Text("Delete permanently (can't be undone)", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { if (title.isNotBlank()) onConfirm(title, content, type) }, enabled = title.isNotBlank(), modifier = Modifier.testTag("note_save_button")) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun CreateProjectDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String, String) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("Tech & Engineering") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Project Initiative", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Project Title") },
                    placeholder = { Text("e.g. Android AI Redesign") },
                    modifier = Modifier.fillMaxWidth().testTag("new_project_title_input"),
                    singleLine = true
                )

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Overview & Objectives") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2
                )

                OutlinedTextField(
                    value = category,
                    onValueChange = { category = it },
                    label = { Text("Category") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (title.isNotBlank()) onConfirm(title, description, category)
                },
                enabled = title.isNotBlank(),
                modifier = Modifier.testTag("save_project_confirm_btn")
            ) {
                Text("Create Project")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun AddMilestoneDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var title by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Milestone", style = MaterialTheme.typography.titleMedium) },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Milestone Deliverable") },
                placeholder = { Text("e.g. Implement Voice Visualizer") },
                modifier = Modifier.fillMaxWidth().testTag("new_milestone_title_input"),
                singleLine = true
            )
        },
        confirmButton = {
            Button(
                onClick = { if (title.isNotBlank()) onConfirm(title) },
                enabled = title.isNotBlank(),
                modifier = Modifier.testTag("save_milestone_confirm_btn")
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
