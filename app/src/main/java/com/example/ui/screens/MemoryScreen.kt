package com.example.ui.screens

import com.example.ui.theme.auraSwitchColors
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import com.example.data.model.MemoryCategory
import com.example.data.model.MemoryEntity
import com.example.data.model.sourceLabel
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudDone
import com.example.viewmodel.AuraViewModel

@Composable
fun MemoryScreen(viewModel: AuraViewModel) {
    val memories by viewModel.filteredMemories.collectAsState()
    val allMemories by viewModel.allMemories.collectAsState()
    val searchQuery by viewModel.memorySearchQuery.collectAsState()
    val sharingOn by viewModel.sharePersonalContext.collectAsState()
    val selectedCategory by viewModel.selectedMemoryCategory.collectAsState()

    var showAddDialog by remember { mutableStateOf(false) }
    var memoryPendingDelete by remember { mutableStateOf<MemoryEntity?>(null) }
    var memoryBeingEdited by remember { mutableStateOf<MemoryEntity?>(null) }
    var showClearAllDialog by remember { mutableStateOf(false) }

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
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Persistent Memory",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "${allMemories.count { it.isActive }} active facts & preferences retained across sessions",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        if (allMemories.isNotEmpty()) {
                            TextButton(
                                onClick = { showClearAllDialog = true },
                                modifier = Modifier.testTag("clear_all_memories_button")
                            ) {
                                Text("Clear all", color = MaterialTheme.colorScheme.error)
                            }
                        }

                        Icon(
                            imageVector = Icons.Default.Psychology,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    // Search field
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { viewModel.memorySearchQuery.value = it },
                        placeholder = { Text("Search stored memories...") },
                        leadingIcon = {
                            Icon(Icons.Default.Search, contentDescription = "Search", tint = MaterialTheme.colorScheme.primary)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("memory_search_input"),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true
                    )

                    Spacer(Modifier.height(10.dp))

                    // Category filter chips
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        item {
                            FilterChip(
                                label = "All",
                                isSelected = selectedCategory == null,
                                onClick = { viewModel.selectedMemoryCategory.value = null }
                            )
                        }
                        items(MemoryCategory.values()) { category ->
                            FilterChip(
                                label = category.displayName,
                                isSelected = selectedCategory == category,
                                onClick = {
                                    viewModel.selectedMemoryCategory.value =
                                        if (selectedCategory == category) null else category
                                }
                            )
                        }
                    }
                }
            }

            // Memory list
            if (memories.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = if (searchQuery.isNotBlank()) "No memories match \"$searchQuery\"" else "No persistent memories recorded yet",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Tap + to add one. In Auto or Local mode you can also start a chat message with \"Remember…\". Claude can't save memories.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(memories, key = { it.id }) { memory ->
                        MemoryItemCard(
                            memory = memory,
                            onToggleActive = { viewModel.toggleMemoryActive(memory) },
                            onDelete = { memoryPendingDelete = memory },
                            onEdit = { memoryBeingEdited = memory },
                            onToggleShare = { viewModel.toggleMemoryShare(memory) },
                            sharingOn = sharingOn
                        )
                    }
                }
            }
        }

        // FAB to add memory manually
        FloatingActionButton(
            onClick = { showAddDialog = true },
            containerColor = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp)
                .testTag("add_memory_fab")
        ) {
            Icon(Icons.Default.Add, contentDescription = "Add Memory", tint = MaterialTheme.colorScheme.onPrimary)
        }
    }

    memoryPendingDelete?.let { memory ->
        AlertDialog(
            onDismissRequest = { memoryPendingDelete = null },
            title = { Text("Delete memory?") },
            text = { Text("Delete this saved memory? This cannot be undone.") },
            confirmButton = {
                Button(onClick = {
                    viewModel.deleteMemory(memory)
                    memoryPendingDelete = null
                }, modifier = Modifier.testTag("confirm_delete_memory_button")) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { memoryPendingDelete = null }) { Text("Cancel") }
            }
        )
    }

    if (showClearAllDialog) {
        AlertDialog(
            onDismissRequest = { showClearAllDialog = false },
            title = { Text("Clear all memories?") },
            text = { Text("This permanently deletes all saved memories. Your chats, projects, and tasks will not be affected.") },
            confirmButton = {
                Button(onClick = {
                    viewModel.clearAllMemories()
                    showClearAllDialog = false
                }, modifier = Modifier.testTag("confirm_clear_all_memories_button")) {
                    Text("Clear all")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearAllDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showAddDialog) {
        AddMemoryDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { content, cat, importance ->
                viewModel.addManualMemory(content, cat, importance)
                showAddDialog = false
            }
        )
    }

    memoryBeingEdited?.let { memory ->
        AddMemoryDialog(
            onDismiss = { memoryBeingEdited = null },
            onConfirm = { content, cat, importance ->
                viewModel.editMemory(memory, content, cat, importance)
                memoryBeingEdited = null
            },
            initial = memory
        )
    }
}

@Composable
fun FilterChip(label: String, isSelected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.clickable { onClick() }
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

@Composable
fun MemoryItemCard(
    memory: MemoryEntity,
    onToggleActive: () -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit = {},
    onToggleShare: () -> Unit = {},
    /** The Settings sharing switch; marked memories are only sent while it's on. */
    sharingOn: Boolean = false
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClickLabel = "Edit memory", onClick = onEdit)
            .testTag("memory_item_${memory.id}"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (memory.isActive)
                MaterialTheme.colorScheme.surfaceVariant
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Category badge
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = memory.category.displayName,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Importance stars
                    Row {
                        repeat(memory.importance) {
                            Icon(
                                imageVector = Icons.Default.Star,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.size(13.dp)
                            )
                        }
                    }

                    Spacer(Modifier.width(8.dp))

                    // Active toggle switch
                    Switch(
                        checked = memory.isActive,
                        onCheckedChange = { onToggleActive() },
                        colors = auraSwitchColors(),
                        modifier = Modifier.testTag("toggle_memory_${memory.id}")
                    )

                    Spacer(Modifier.width(4.dp))

                    IconButton(onClick = onEdit, modifier = Modifier.size(32.dp).testTag("edit_memory_${memory.id}")) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Edit Memory",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete Memory",
                            tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            Text(
                text = memory.content,
                style = MaterialTheme.typography.bodyMedium,
                color = if (memory.isActive)
                    MaterialTheme.colorScheme.onSurface
                else
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )

            Spacer(Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Where this memory came from, so the user can judge it (and spot old demo data).
                Text(
                    text = memory.sourceLabel + (memory.updatedAt?.let { " · edited " + formatMemoryDate(it) } ?: ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).testTag("memory_source_${memory.id}")
                )
                // Per-memory consent to send it to Claude.
                androidx.compose.material3.FilterChip(
                    selected = memory.shareWithCloud,
                    onClick = onToggleShare,
                    label = {
                        Text(
                            memoryShareLabel(memory.shareWithCloud, memory.isActive, sharingOn),
                            style = MaterialTheme.typography.labelSmall
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = if (memory.shareWithCloud) Icons.Default.CloudDone else Icons.Default.CloudOff,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                    },
                    modifier = Modifier.testTag("share_memory_${memory.id}")
                )
            }
        }
    }
}

private fun formatMemoryDate(millis: Long): String =
    java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(millis))

@Composable
fun AddMemoryDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, MemoryCategory, Int) -> Unit,
    /** When set, the dialog edits this memory instead of adding a new one. */
    initial: MemoryEntity? = null
) {
    var content by remember { mutableStateOf(initial?.content ?: "") }
    var category by remember { mutableStateOf(initial?.category ?: MemoryCategory.PREFERENCE) }
    var importance by remember { mutableFloatStateOf((initial?.importance ?: 3).toFloat()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add memory" else "Edit memory", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("Memory Fact or Preference") },
                    placeholder = { Text("e.g. Always generate modular Kotlin code") },
                    modifier = Modifier.fillMaxWidth().testTag("memory_content_input"),
                    minLines = 2
                )

                Spacer(Modifier.height(12.dp))

                Text("Category", style = MaterialTheme.typography.labelSmall)
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                ) {
                    items(MemoryCategory.values()) { cat ->
                        FilterChip(
                            label = cat.displayName,
                            isSelected = category == cat,
                            onClick = { category = cat }
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                Text("Importance: ${importance.toInt()} / 5", style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = importance,
                    onValueChange = { importance = it },
                    valueRange = 1f..5f,
                    steps = 3,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (content.isNotBlank()) {
                        onConfirm(content, category, importance.toInt())
                    }
                },
                enabled = content.isNotBlank(),
                modifier = Modifier.testTag("save_memory_confirm_btn")
            ) {
                Text(if (initial == null) "Save to Memory" else "Save changes")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

/** What the share chip says, including why a marked memory isn't being sent right now. */
fun memoryShareLabel(marked: Boolean, active: Boolean, sharingOn: Boolean): String = when {
    !marked -> "Share with Claude"
    !active -> "Marked · memory is off"
    !sharingOn -> "Marked · sharing is off in Settings"
    else -> "Shared with Claude"
}
