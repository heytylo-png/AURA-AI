package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.model.ChatImages
import com.example.data.model.MediaLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What can be done with a picture or video shown full screen. Provided by the screen that shows it. */
interface MediaViewerActions {
    fun saveToGallery(name: String)
    fun share(name: String)
    fun addToLibrary(name: String, label: String, reference: Boolean)
    /** OpenAI image and video creation is on (the user's switch). */
    val canCreate: Boolean
    fun editImage(name: String, prompt: String)
    fun videoFromImage(name: String, prompt: String, seconds: Int)
    fun makeSticker(name: String)
    fun useAsAlertSound(name: String)
}

val LocalMediaViewerActions = staticCompositionLocalOf<MediaViewerActions?> { null }

/**
 * A stored picture (shown as large as it was kept) or video (played with the phone's controls), full
 * screen, with actions underneath: save to the gallery, share, keep in the library, and the creative
 * ones when creation is on.
 */
@Composable
fun MediaViewer(name: String, onClose: () -> Unit, topStart: (@Composable () -> Unit)? = null) {
    val context = LocalContext.current
    val actions = LocalMediaViewerActions.current
    val file = remember(name) { MediaLibrary.find(context, name) }
    val video = MediaLibrary.isVideoFile(name)
    var dialog by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black).testTag(if (video) "video_player" else "image_viewer")) {
            Box(modifier = Modifier.fillMaxSize().padding(bottom = 72.dp), contentAlignment = Alignment.Center) {
                when {
                    file == null -> Text("This is no longer on this phone.", color = Color.White)
                    video -> AndroidView(
                        factory = { ctx ->
                            android.widget.VideoView(ctx).apply {
                                val controls = android.widget.MediaController(ctx)
                                controls.setAnchorView(this)
                                setMediaController(controls)
                                setVideoURI(android.net.Uri.fromFile(file))
                                setOnPreparedListener { start(); controls.show(3000) }
                                setOnErrorListener { _, _, _ -> true }
                            }
                        },
                        onRelease = { it.stopPlayback() },
                        modifier = Modifier.fillMaxWidth()
                    )
                    else -> {
                        val bitmap by produceState<ImageBitmap?>(null, name) {
                            value = withContext(Dispatchers.IO) { ChatImages.thumbnail(file, 2048)?.asImageBitmap() }
                        }
                        bitmap?.let {
                            androidx.compose.foundation.Image(
                                bitmap = it, contentDescription = "Image", contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            }
            topStart?.let { Box(modifier = Modifier.align(Alignment.TopStart).padding(8.dp)) { it() } }
            IconButton(
                onClick = onClose,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).testTag(if (video) "close_video_player" else "close_image_viewer")
            ) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
            }
            if (file != null && actions != null) {
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    ViewerAction(Icons.Default.Download, "Save", "viewer_save") { actions.saveToGallery(name) }
                    ViewerAction(Icons.Default.Share, "Share", "viewer_share") { actions.share(name) }
                    if (!MediaLibrary.isValidName(name)) {
                        ViewerAction(Icons.Default.Bookmark, "Library", "viewer_library") { dialog = "library" }
                    }
                    if (video) {
                        ViewerAction(Icons.Default.NotificationsActive, "Alert sound", "viewer_alert_sound") { dialog = "alert" }
                    } else if (actions.canCreate) {
                        ViewerAction(Icons.Default.AutoFixHigh, "Edit", "viewer_edit") { dialog = "edit" }
                        ViewerAction(Icons.Default.Movie, "Make video", "viewer_make_video") { dialog = "video" }
                        ViewerAction(Icons.Default.EmojiEmotions, "Sticker", "viewer_sticker") { dialog = "sticker" }
                    }
                }
            }
        }
    }

    if (actions != null) when (dialog) {
        "library" -> AddToLibraryDialog(
            onDismiss = { dialog = null },
            onSave = { label, reference -> actions.addToLibrary(name, label, reference); dialog = null }
        )
        "edit" -> PromptDialog(
            title = "Edit this image",
            hint = "What should change? For example \"make it a watercolor\" or \"add a party hat\".",
            confirm = "Edit",
            note = "The image and your words go to OpenAI. The edited copy appears in the chat; this one is kept.",
            onDismiss = { dialog = null },
            onConfirm = { prompt, _ -> actions.editImage(name, prompt); dialog = null; onClose() }
        )
        "video" -> PromptDialog(
            title = "Make a video from this image",
            hint = "What happens? For example \"the dog runs toward the camera\".",
            confirm = "Make video",
            note = "The image is the first frame. It goes to OpenAI with your words. Videos cost much more than images " +
                "(roughly 10 cents per second) and take a few minutes.",
            seconds = true,
            onDismiss = { dialog = null },
            onConfirm = { prompt, seconds -> actions.videoFromImage(name, prompt, seconds); dialog = null; onClose() }
        )
        "sticker" -> ConfirmDialog(
            title = "Make a sticker?",
            text = "OpenAI cuts out the main subject and gives it a white sticker border on a transparent background. " +
                "The image goes to OpenAI. The sticker is saved to your library and shown in the chat.",
            confirm = "Make sticker",
            onDismiss = { dialog = null },
            onConfirm = { actions.makeSticker(name); dialog = null; onClose() }
        )
        "alert" -> ConfirmDialog(
            title = "Use this video's sound for reminders?",
            text = "The first 30 seconds of its sound become the sound of Aura's to-do reminders. It's saved in your phone's " +
                "notification sounds as \"Aura reminder\". You can go back to the default sound in Settings.",
            confirm = "Use sound",
            onDismiss = { dialog = null },
            onConfirm = { actions.useAsAlertSound(name); dialog = null }
        )
    }
}

@Composable
private fun ViewerAction(icon: ImageVector, label: String, tag: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .widthIn(min = 64.dp)
            .clickable(onClickLabel = label, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .testTag(tag),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
        Text(label, color = Color.White, style = MaterialTheme.typography.labelSmall)
    }
}

/** Name an item for the library, and choose whether Claude may be shown it. */
@Composable
fun AddToLibraryDialog(
    initialLabel: String = "",
    initialReference: Boolean = false,
    title: String = "Save to your library",
    onDismiss: () -> Unit,
    onSave: (label: String, reference: Boolean) -> Unit
) {
    var label by remember { mutableStateOf(initialLabel) }
    var reference by remember { mutableStateOf(initialReference) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it.take(MediaLibrary.MAX_LABEL) },
                    label = { Text("Name (optional)") },
                    placeholder = { Text("e.g. my dog") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("library_label")
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = reference,
                        onCheckedChange = { reference = it },
                        enabled = label.isNotBlank(),
                        modifier = Modifier.testTag("library_reference")
                    )
                    Text(
                        "Let Claude see it when I mention its name",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Text(
                    "When a message you send to Claude includes the name, this picture goes with it, so Claude knows what you mean. " +
                        "Only for pictures; up to 3 per message.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(label.trim(), reference && label.isNotBlank()) }, modifier = Modifier.testTag("library_save")) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun PromptDialog(
    title: String,
    hint: String,
    confirm: String,
    note: String,
    seconds: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (prompt: String, seconds: Int) -> Unit
) {
    var prompt by remember { mutableStateOf("") }
    var length by remember { mutableStateOf(4) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it.take(1000) },
                    placeholder = { Text(hint) },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth().testTag("media_prompt")
                )
                if (seconds) {
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Length:", style = MaterialTheme.typography.bodyMedium)
                        listOf(4, 8).forEach { s ->
                            TextButton(onClick = { length = s }) {
                                Text(if (length == s) "✓ ${s}s" else "${s}s")
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(prompt.trim(), length) }, enabled = prompt.isNotBlank(), modifier = Modifier.testTag("media_prompt_confirm")) {
                Text(confirm)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun ConfirmDialog(title: String, text: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm, modifier = Modifier.testTag("media_confirm")) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** A small thumbnail of a stored picture or video, for grids and lists. */
@Composable
fun MediaThumbnail(name: String, modifier: Modifier = Modifier, maxEdge: Int = 360) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(null, name) {
        value = withContext(Dispatchers.IO) {
            val file = MediaLibrary.find(context, name) ?: return@withContext null
            if (MediaLibrary.isVideoFile(name)) com.example.data.model.ChatVideos.poster(file, maxEdge)?.asImageBitmap()
            else ChatImages.thumbnail(file, maxEdge)?.asImageBitmap()
        }
    }
    Box(modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
        bitmap?.let {
            androidx.compose.foundation.Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        if (MediaLibrary.isVideoFile(name)) {
            Icon(
                androidx.compose.material.icons.Icons.Default.Movie, contentDescription = "Video",
                tint = Color.White, modifier = Modifier.align(Alignment.BottomStart).padding(4.dp).size(16.dp)
            )
        }
    }
}
