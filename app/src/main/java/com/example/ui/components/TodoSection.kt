package com.example.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.example.data.model.ProjectEntity
import com.example.data.model.TodoEntity
import com.example.data.model.TodoRules

private const val DAY_MS = 86_400_000L

/** The to-do part of the Tasks tab: open to-dos, then collapsible Done and Recently deleted. */
fun LazyListScope.todoItems(
    visible: List<TodoEntity>,
    deleted: List<TodoEntity>,
    projects: List<ProjectEntity>,
    today: Long,
    showDone: Boolean,
    onShowDone: (Boolean) -> Unit,
    showDeleted: Boolean,
    onShowDeleted: (Boolean) -> Unit,
    onToggle: (TodoEntity) -> Unit,
    onEdit: (TodoEntity) -> Unit,
    onDelete: (TodoEntity) -> Unit,
    onRestore: (TodoEntity) -> Unit,
    onDeleteForever: (TodoEntity) -> Unit
) {
    val open = visible.filter { !it.done }.sortedWith(TodoRules.openOrder)
    val done = visible.filter { it.done }.sortedByDescending { it.doneAt ?: 0 }
    val projectName = { id: Long? -> projects.firstOrNull { it.id == id }?.title }

    item(key = "todo-header") { SectionHeader("To-dos", "${open.size} open") }
    if (open.isEmpty()) {
        item(key = "todo-empty") {
            Text(
                "Nothing to do. Tap + to add a to-do.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }
    }
    items(open, key = { "todo-${it.id}" }) { todo ->
        TodoRow(todo, projectName(todo.projectId), today, onToggle, onEdit, onDelete)
    }
    if (done.isNotEmpty()) {
        item(key = "todo-done-toggle") { Collapser("Done", done.size, showDone, onShowDone, "todo_done_toggle") }
        if (showDone) items(done, key = { "todo-${it.id}" }) { todo ->
            TodoRow(todo, projectName(todo.projectId), today, onToggle, onEdit, onDelete)
        }
    }
    if (deleted.isNotEmpty()) {
        item(key = "todo-deleted-toggle") { Collapser("Recently deleted", deleted.size, showDeleted, onShowDeleted, "todo_deleted_toggle") }
        if (showDeleted) {
            item(key = "todo-deleted-note") {
                Text(
                    "Kept for ${TodoRules.KEEP_DELETED_DAYS} days, then removed for good.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            items(deleted, key = { "todo-deleted-${it.id}" }) { todo -> DeletedRow(todo, onRestore, onDeleteForever) }
        }
    }
}

@Composable
private fun SectionHeader(title: String, detail: String) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.size(8.dp))
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Collapser(title: String, count: Int, open: Boolean, onChange: (Boolean) -> Unit, tag: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { onChange(!open) }
            .padding(vertical = 8.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.size(6.dp))
        Text("$title ($count)", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TodoRow(
    todo: TodoEntity,
    projectName: String?,
    today: Long,
    onToggle: (TodoEntity) -> Unit,
    onEdit: (TodoEntity) -> Unit,
    onDelete: (TodoEntity) -> Unit
) {
    val overdue = TodoRules.isOverdue(todo, today)
    Card(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClickLabel = "Edit to-do") { onEdit(todo) }.testTag("todo_${todo.id}"),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(Modifier.padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onToggle(todo) }, modifier = Modifier.testTag("todo_toggle_${todo.id}")) {
                Icon(
                    imageVector = if (todo.done) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                    contentDescription = if (todo.done) "Reopen to-do" else "Mark done",
                    tint = if (todo.done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text(
                    todo.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (todo.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    textDecoration = if (todo.done) TextDecoration.LineThrough else null
                )
                val details = listOfNotNull(
                    todo.dueEpochDay?.let { dueLabel(it, today) },
                    projectName,
                    todo.source?.label
                ).joinToString(" · ")
                if (details.isNotEmpty()) {
                    Text(
                        details,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (overdue) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (overdue) FontWeight.SemiBold else null
                    )
                }
            }
            IconButton(onClick = { onDelete(todo) }, modifier = Modifier.testTag("todo_delete_${todo.id}")) {
                Icon(Icons.Default.Delete, contentDescription = "Delete to-do", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun DeletedRow(todo: TodoEntity, onRestore: (TodoEntity) -> Unit, onDeleteForever: (TodoEntity) -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            todo.title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = { onRestore(todo) }, modifier = Modifier.testTag("todo_restore_${todo.id}")) {
            Icon(Icons.Default.Restore, null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.size(4.dp))
            Text("Restore")
        }
        TextButton(onClick = { confirm = true }, modifier = Modifier.testTag("todo_delete_forever_${todo.id}")) {
            Text("Delete", color = MaterialTheme.colorScheme.error)
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Delete for good?") },
            text = { Text("\"${todo.title}\" will be removed and can't be restored.") },
            confirmButton = { TextButton(onClick = { confirm = false; onDeleteForever(todo) }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } }
        )
    }
}

/** "Due today", "Due tomorrow", "Overdue · 2026-11-01" or "Due 2026-11-20". */
fun dueLabel(due: Long, today: Long): String = when {
    due < today -> "Overdue · ${TodoRules.formatDue(due)}"
    due == today -> "Due today"
    due == today + 1 -> "Due tomorrow"
    else -> "Due ${TodoRules.formatDue(due)}"
}

/** Add or edit a to-do. [onSave] returns false when a limit is broken; the dialog then stays open. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodoEditorDialog(
    initial: TodoEntity?,
    projects: List<ProjectEntity>,
    onSave: (title: String, notes: String, dueEpochDay: Long?, projectId: Long?) -> Boolean,
    onDismiss: () -> Unit,
    /** For a new to-do: the project to pre-select. */
    presetProjectId: Long? = null
) {
    var title by remember { mutableStateOf(initial?.title ?: "") }
    var notes by remember { mutableStateOf(initial?.notes ?: "") }
    var due by remember { mutableStateOf(initial?.dueEpochDay) }
    var projectId by remember { mutableStateOf(initial?.projectId ?: presetProjectId) }
    var pickingDate by remember { mutableStateOf(false) }
    var pickingProject by remember { mutableStateOf(false) }
    val valid = TodoRules.clean(title, notes) != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "New to-do" else "Edit to-do") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = title, onValueChange = { title = it }, label = { Text("To-do") },
                    singleLine = true, modifier = Modifier.fillMaxWidth().testTag("todo_title_field")
                )
                OutlinedTextField(
                    value = notes, onValueChange = { notes = it }, label = { Text("Notes (optional)") },
                    minLines = 2, modifier = Modifier.fillMaxWidth().testTag("todo_notes_field")
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { pickingDate = true }, modifier = Modifier.weight(1f).testTag("todo_due_button")) {
                        Text(due?.let { "Due ${TodoRules.formatDue(it)}" } ?: "Add due date")
                    }
                    if (due != null) TextButton(onClick = { due = null }) { Text("Clear") }
                }
                Box {
                    OutlinedButton(onClick = { pickingProject = true }, modifier = Modifier.fillMaxWidth().testTag("todo_project_button")) {
                        Text(projects.firstOrNull { it.id == projectId }?.title ?: "No project")
                    }
                    DropdownMenu(expanded = pickingProject, onDismissRequest = { pickingProject = false }) {
                        DropdownMenuItem(text = { Text("No project") }, onClick = { projectId = null; pickingProject = false })
                        projects.forEach { p ->
                            DropdownMenuItem(text = { Text(p.title) }, onClick = { projectId = p.id; pickingProject = false })
                        }
                    }
                }
                Text(
                    "A due date is shown in the list; Aura doesn't send reminders yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { if (onSave(title, notes, due, projectId)) onDismiss() }, enabled = valid, modifier = Modifier.testTag("todo_save_button")) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )

    if (pickingDate) {
        // DatePicker works in UTC midnights; a day number maps 1:1.
        val state = rememberDatePickerState(initialSelectedDateMillis = due?.let { it * DAY_MS })
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { due = Math.floorDiv(it, DAY_MS) }
                    pickingDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickingDate = false }) { Text("Cancel") } }
        ) {
            DatePicker(state = state)
        }
    }
}

