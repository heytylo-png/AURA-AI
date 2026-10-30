package com.example.ui.screens

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
import com.example.viewmodel.AuraViewModel

@Composable
fun MemoryScreen(viewModel: AuraViewModel) {
    val memories by viewModel.filteredMemories.collectAsState()
    val allMemories by viewModel.allMemories.collectAsState()
    val searchQuery by viewModel.memorySearchQuery.collectAsState()
    val selectedCategory by viewModel.selectedMemoryCategory.collectAsState()

    var showAddDialog by remember { mutableStateOf(false) }
    var memoryPendingDelete by remember { mutableStateOf<MemoryEntity?>(null) }
    var showClearAllDialog by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
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
                            text = "Tell Aura in Chat (\"Remember that I prefer...\") or tap + to manually store a knowledge item.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
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
                            onDelete = { memoryPendingDelete = memory }
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
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
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
                                tint = Color(0xFFFFB300),
                                modifier = Modifier.size(13.dp)
                            )
                        }
                    }

                    Spacer(Modifier.width(8.dp))

                    // Active toggle switch
                    Switch(
                        checked = memory.isActive,
                        onCheckedChange = { onToggleActive() },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.primary
                        ),
                        modifier = Modifier.size(36.dp).testTag("toggle_memory_${memory.id}")
                    )

                    Spacer(Modifier.width(4.dp))

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
        }
    }
}

@Composable
fun AddMemoryDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, MemoryCategory, Int) -> Unit
) {
    var content by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(MemoryCategory.PREFERENCE) }
    var importance by remember { mutableFloatStateOf(3f) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Store Persistent Memory", style = MaterialTheme.typography.titleMedium) },
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
                Text("Save to Memory")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
