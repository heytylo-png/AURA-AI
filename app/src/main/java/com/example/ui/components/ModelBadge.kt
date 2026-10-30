package com.example.ui.components

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Info
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
import androidx.compose.ui.graphics.Color
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
    val badgeBg = if (isLocal) Color(0xFF0D2818) else Color(0xFF1B1A3A)
    val badgeBorder = if (isLocal) Color(0xFF00E676) else Color(0xFF9D4EDD)
    val textColor = if (isLocal) Color(0xFF69F0AE) else Color(0xFFE0AAFF)

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(badgeBg)
            .clickable { if (!reasoningExplanation.isNullOrBlank()) showDialog = true }
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .testTag("model_badge_${modelUsed.name.lowercase()}")
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = if (isLocal) Icons.Default.Bolt else Icons.Default.Cloud,
                contentDescription = null,
                tint = textColor,
                modifier = Modifier.size(13.dp)
            )
            Text(
                text = if (isLocal) "Edge Local" else "Gemini 3.8",
                color = textColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
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
                        imageVector = if (isLocal) Icons.Default.Bolt else Icons.Default.Cloud,
                        contentDescription = null,
                        tint = textColor,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (isLocal) "Local Offline Reasoning" else "Gemini 3.8 Flash Cloud",
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
                        text = if (isLocal)
                            "Local execution guarantees zero data egress, instant response, and uninterrupted offline availability."
                        else
                            "Cloud execution harnesses full frontier reasoning via Firebase AI Logic and the Gemini API.",
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
