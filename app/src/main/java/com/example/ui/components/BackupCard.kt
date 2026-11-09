package com.example.ui.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.backup.BackupCrypto
import com.example.data.model.TodoRules
import com.example.viewmodel.AuraViewModel
import com.example.viewmodel.AuraViewModel.BackupUiState

/** Settings card: save an encrypted backup to a file you choose, or restore one. */
@Composable
fun BackupCard(viewModel: AuraViewModel) {
    val state by viewModel.backupState.collectAsState()
    var askExportPassphrase by remember { mutableStateOf(false) }
    var exportPassphrase by remember { mutableStateOf<CharArray?>(null) }
    var restoreUri by remember { mutableStateOf<android.net.Uri?>(null) }

    val createFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val pass = exportPassphrase
        exportPassphrase = null
        if (uri != null && pass != null) viewModel.exportBackup(uri, pass) else pass?.fill(' ')
    }
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> restoreUri = uri }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Backup", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Saves your chats, memories, projects, to-dos and settings to one file, encrypted with a passphrase you choose. " +
                    "Keep the file somewhere safe (for example Google Drive or a computer). Aura never stores the passphrase, and a forgotten passphrase can't be recovered.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { askExportPassphrase = true },
                    enabled = state !is BackupUiState.Working,
                    modifier = Modifier.weight(1f).testTag("backup_export_button")
                ) { Text("Save backup") }
                OutlinedButton(
                    onClick = { openFile.launch(arrayOf("*/*")) },
                    enabled = state !is BackupUiState.Working,
                    modifier = Modifier.weight(1f).testTag("backup_restore_button")
                ) { Text("Restore") }
            }
            when (val s = state) {
                is BackupUiState.Working -> StatusLine(s.what, MaterialTheme.colorScheme.onSurfaceVariant)
                is BackupUiState.Done -> StatusLine(s.message, MaterialTheme.colorScheme.primary)
                is BackupUiState.Failed -> StatusLine(s.message, MaterialTheme.colorScheme.error)
                else -> Unit
            }
        }
    }

    if (askExportPassphrase) {
        PassphraseDialog(
            title = "Choose a passphrase",
            confirmLabel = "Choose where to save",
            needsConfirmation = true,
            onDismiss = { askExportPassphrase = false },
            onConfirm = { pass ->
                askExportPassphrase = false
                exportPassphrase = pass
                createFile.launch("aura-backup-${TodoRules.formatDue(TodoRules.today())}.aura")
            }
        )
    }

    restoreUri?.let { uri ->
        PassphraseDialog(
            title = "Enter the backup's passphrase",
            confirmLabel = "Unlock",
            needsConfirmation = false,
            onDismiss = { restoreUri = null },
            onConfirm = { pass ->
                restoreUri = null
                viewModel.inspectBackup(uri, pass)
            }
        )
    }

    (state as? BackupUiState.ConfirmRestore)?.let { s ->
        val summary = s.summary
        AlertDialog(
            onDismissRequest = { viewModel.dismissBackupMessage() },
            title = { Text("Replace the data on this phone?") },
            text = {
                Text(
                    "Backup from ${TodoRules.formatDue(TodoRules.today(summary.createdAt))}: ${summary.memories} memories, " +
                        "${summary.chats} chats, ${summary.todos} to-dos, ${summary.projects} projects.\n\n" +
                        "Everything currently in Aura on this phone will be replaced, and Aura will restart. " +
                        "The current data is kept as a safety copy on this phone until the next restore.\n\n" +
                        "If this is a new phone, Claude needs its new access ID added to the relay again (Settings shows it)."
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmRestore() }, modifier = Modifier.testTag("backup_confirm_restore")) {
                    Text("Replace and restart", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { viewModel.dismissBackupMessage() }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun StatusLine(text: String, color: androidx.compose.ui.graphics.Color) {
    Spacer(Modifier.height(8.dp))
    Text(text, style = MaterialTheme.typography.bodySmall, color = color, modifier = Modifier.testTag("backup_status"))
}

@Composable
private fun PassphraseDialog(
    title: String,
    confirmLabel: String,
    needsConfirmation: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (CharArray) -> Unit
) {
    var pass by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    val longEnough = pass.length >= BackupCrypto.MIN_PASSPHRASE
    val matches = !needsConfirmation || pass == again
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = pass, onValueChange = { pass = it }, label = { Text("Passphrase") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth().testTag("backup_passphrase")
                )
                if (needsConfirmation) {
                    OutlinedTextField(
                        value = again, onValueChange = { again = it }, label = { Text("Type it again") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth().testTag("backup_passphrase_again")
                    )
                }
                Text(
                    text = when {
                        !longEnough -> "At least ${BackupCrypto.MIN_PASSPHRASE} characters."
                        !matches -> "The two don't match."
                        needsConfirmation -> "Write it down. It can't be recovered."
                        else -> ""
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(pass.toCharArray()); pass = ""; again = "" }, enabled = longEnough && matches) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
