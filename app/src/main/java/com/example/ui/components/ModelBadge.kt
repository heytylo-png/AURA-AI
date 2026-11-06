package com.example.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.AiModelUsed

@Composable
fun ModelBadge(
    modelUsed: AiModelUsed,
    latencyMs: Long? = null,
    reasoningExplanation: String? = null,
    modifier: Modifier = Modifier
) {
    var showDialog by remember { mutableStateOf(false) }

    val isLocal = modelUsed == AiModelUsed.LOCAL
    val isNone = modelUsed == AiModelUsed.NONE
    // Quiet outlined tag; it says where the reply came from, so it is shown on every AI message.
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant
    val icon = when { isNone -> Icons.Default.Info; isLocal -> Icons.Default.PhoneAndroid; else -> Icons.Default.Cloud }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp))
            .clickable { if (!reasoningExplanation.isNullOrBlank()) showDialog = true }
            .padding(horizontal = 7.dp, vertical = 3.dp)
            .testTag("model_badge_${modelUsed.name.lowercase()}")
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = textColor,
                modifier = Modifier.size(12.dp)
            )
            Text(
                text = when { isNone -> "Not answered"; isLocal -> "On this phone"; else -> "Claude" },
                color = textColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )
            if (latencyMs != null && latencyMs > 0) {
                Text(
                    text = "• ${latencyMs}ms",
                    color = textColor.copy(alpha = 0.8f),
                    fontSize = 10.sp
                )
            }
            if (!reasoningExplanation.isNullOrBlank()) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = "Details",
                    tint = textColor.copy(alpha = 0.6f),
                    modifier = Modifier.size(11.dp)
                )
            }
        }
    }

    if (showDialog && !reasoningExplanation.isNullOrBlank()) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = textColor,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = when { isNone -> "No answer"; isLocal -> "Answered on this phone"; else -> "Answered by Claude" },
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            },
            text = {
                Column {
                    Text(
                        text = reasoningExplanation,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = when {
                            isNone -> "No model produced this reply. It explains why your message wasn't answered."
                            isLocal -> "Aura's built-in offline replies. They follow simple keyword rules and are not an AI model. The note above says whether anything was sent."
                            else -> "Anthropic's Claude, reached through your private Aura relay."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text("Close")
                }
            }
        )
    }
}
