package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.model.MediaLibrary
import com.example.data.model.SavedMediaEntity

/**
 * The pictures, stickers and videos the user saved. With [onPick], tapping an item chooses it (to attach
 * to a message or a to-do); otherwise it opens the item, with options to rename, change whether Claude may
 * see it, or remove it.
 */
@Composable
fun MediaLibraryScreen(
    items: List<SavedMediaEntity>,
    onDismiss: () -> Unit,
    onUpdate: (SavedMediaEntity) -> Unit,
    onDelete: (SavedMediaEntity) -> Unit,
    onPick: ((SavedMediaEntity) -> Unit)? = null,
    picturesOnly: Boolean = false,
    title: String = if (onPick != null) "Choose from your library" else "Your library"
) {
    var opened by remember { mutableStateOf<SavedMediaEntity?>(null) }
    var editing by remember { mutableStateOf<SavedMediaEntity?>(null) }
    var deleting by remember { mutableStateOf<SavedMediaEntity?>(null) }
    val shown = if (picturesOnly) items.filterNot { it.isVideo } else items

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).testTag("media_library")) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss, modifier = Modifier.testTag("close_media_library")) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }
            if (shown.isEmpty()) {
                Text(
                    text = "Nothing saved yet. Open a picture or video in a chat and tap Library to keep it here. " +
                        "Stickers you make are saved here too.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 104.dp),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(shown, key = { it.id }) { item ->
                        Column(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { if (onPick != null) onPick(item) else opened = item }
                                .testTag("library_item_${item.id}")
                        ) {
                            Box {
                                MediaThumbnail(item.fileName, Modifier.fillMaxWidth().aspectRatio(1f))
                                if (item.isReference) {
                                    Icon(
                                        Icons.Default.Psychology, contentDescription = "Claude can see this when you mention it",
                                        tint = Color.White,
                                        modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(18.dp)
                                            .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(9.dp)).padding(2.dp)
                                    )
                                }
                            }
                            Text(
                                text = item.label.ifBlank {
                                    when (item.kind) { MediaLibrary.KIND_VIDEO -> "Video"; MediaLibrary.KIND_STICKER -> "Sticker"; else -> "Picture" }
                                },
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 2.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    opened?.let { item ->
        MediaViewer(item.fileName, onClose = { opened = null }, topStart = {
            Row {
                TextButton(onClick = { editing = item }, modifier = Modifier.testTag("library_edit")) { Text("Name & Claude", color = Color.White) }
                TextButton(onClick = { deleting = item }, modifier = Modifier.testTag("library_delete")) { Text("Remove", color = Color.White) }
            }
        })
    }
    editing?.let { item ->
        AddToLibraryDialog(
            initialLabel = item.label,
            initialReference = item.isReference,
            title = "Name and Claude access",
            onDismiss = { editing = null },
            onSave = { label, reference ->
                onUpdate(item.copy(label = label, isReference = reference && !item.isVideo))
                editing = null
                opened = null
            }
        )
    }
    deleting?.let { item ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Remove from your library?") },
            text = { Text("It's removed from Aura's library. Copies in chats, the gallery or other apps stay.") },
            confirmButton = {
                TextButton(onClick = { onDelete(item); deleting = null; opened = null }, modifier = Modifier.testTag("library_confirm_delete")) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } }
        )
    }
}
