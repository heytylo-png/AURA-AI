package com.example.ui.components

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.example.data.model.MediaLibrary
import com.example.media.MediaActions
import com.example.viewmodel.AuraViewModel

/** The viewer's actions for the whole app, carried out by [viewModel]. */
@Composable
fun rememberAuraMediaActions(viewModel: AuraViewModel): MediaViewerActions {
    val context = LocalContext.current
    val creationOn by viewModel.mediaGeneration.collectAsState()
    var waitingToSave by remember { mutableStateOf<String?>(null) }
    // Android 9 and older ask for the storage permission before the first save to the gallery.
    val askStorage = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val name = waitingToSave
        waitingToSave = null
        if (name != null) {
            if (granted) viewModel.saveToGallery(name) else viewModel.voiceNotice("Saving to the gallery needs the storage permission.")
        }
    }
    return remember(viewModel, creationOn) {
        object : MediaViewerActions {
            override val canCreate = creationOn

            override fun saveToGallery(name: String) {
                if (MediaActions.needsStoragePermission(context)) {
                    waitingToSave = name
                    askStorage.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                } else {
                    viewModel.saveToGallery(name)
                }
            }

            override fun share(name: String) {
                val file = MediaLibrary.find(context, name) ?: return viewModel.voiceNotice("It's no longer on this phone.")
                runCatching { context.startActivity(MediaActions.shareIntent(context, file)) }
                    .onFailure { viewModel.voiceNotice("No app on this phone can take it.") }
            }

            override fun addToLibrary(name: String, label: String, reference: Boolean) = viewModel.addToLibrary(name, label, reference)
            override fun editImage(name: String, prompt: String) = viewModel.editImage(name, prompt)
            override fun videoFromImage(name: String, prompt: String, seconds: Int) = viewModel.videoFromImage(name, prompt, seconds)
            override fun makeSticker(name: String) = viewModel.makeSticker(name)
            override fun useAsAlertSound(name: String) = viewModel.useAsAlertSound(name)
        }
    }
}
