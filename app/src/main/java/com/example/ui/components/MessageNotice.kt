package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.AiModelUsed

/**
 * Splits a stored AI message into the reply text and a status notice, so warnings and failures
 * get their own colour instead of being buried in the text. Works on messages already saved:
 * it reads the exact wording the router writes (HybridAiEngine / AuraViewModel).
 */
object MessageNotice {
    enum class Kind { INFO, WARN, ERROR }

    data class Notice(val kind: Kind, val title: String, val detail: String)
    /** [noticeAfterBody]: show the notice under the text (an answer that was cut off), else above it. */
    data class Presentation(val body: String, val notice: Notice?, val noticeAfterBody: Boolean = false)

    private const val INCOMPLETE = "\n\n⚠ This answer is incomplete: "
    private const val UNAVAILABLE = "⚠ Claude is unavailable ("
    private const val UNAVAILABLE_END = "). Offline reply:\n\n"
    private const val CLOUD_FAILED = "Claude couldn't answer this message."
    private const val ENGINE_ERROR = "Error: "

    fun present(text: String, modelUsed: AiModelUsed?): Presentation {
        val incomplete = text.lastIndexOf(INCOMPLETE)
        if (incomplete >= 0) {
            return Presentation(
                body = text.substring(0, incomplete),
                notice = Notice(Kind.WARN, "This answer is incomplete", sentence(text.substring(incomplete + INCOMPLETE.length))),
                noticeAfterBody = true
            )
        }
        if (text.startsWith(UNAVAILABLE)) {
            val end = text.indexOf(UNAVAILABLE_END)
            if (end > 0) {
                return Presentation(
                    body = text.substring(end + UNAVAILABLE_END.length),
                    notice = Notice(
                        Kind.WARN,
                        "Claude is unavailable",
                        sentence(text.substring(UNAVAILABLE.length, end)) + " Below is Aura's offline reply."
                    )
                )
            }
        }
        if (modelUsed == AiModelUsed.NONE) {
            return when {
                text.startsWith(CLOUD_FAILED) -> Presentation(
                    "",
                    Notice(Kind.ERROR, "Claude couldn't answer", text.removePrefix(CLOUD_FAILED).trim().ifBlank { "No reply was received." })
                )
                text.startsWith(ENGINE_ERROR) -> Presentation(
                    "",
                    Notice(Kind.ERROR, "Something went wrong", text.removePrefix(ENGINE_ERROR).trim())
                )
                else -> Presentation("", Notice(Kind.INFO, "Not answered", text.trim()))
            }
        }
        return Presentation(text, null)
    }

    /** Capitalises and ends the reason with a full stop: "the stream ended early" -> "The stream ended early." */
    private fun sentence(raw: String): String {
        val s = raw.trim()
        if (s.isEmpty()) return s
        val capital = s.replaceFirstChar { it.uppercaseChar() }
        return if (capital.last() in ".!?") capital else "$capital."
    }
}

@Composable
fun NoticeBox(notice: MessageNotice.Notice, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val (fg, bg, icon) = when (notice.kind) {
        MessageNotice.Kind.WARN -> Triple(colors.tertiary, colors.tertiaryContainer, Icons.Outlined.WarningAmber)
        MessageNotice.Kind.ERROR -> Triple(colors.error, colors.errorContainer, Icons.Outlined.ErrorOutline)
        MessageNotice.Kind.INFO -> Triple(colors.onSurfaceVariant, colors.surfaceVariant, Icons.Outlined.Info)
    }
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(bg, shape)
            .border(1.dp, fg.copy(alpha = 0.7f), shape)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("message_notice_${notice.kind.name.lowercase()}"),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.padding(top = 1.dp).size(18.dp))
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(notice.title, style = MaterialTheme.typography.labelLarge, color = fg, fontWeight = FontWeight.SemiBold)
            if (notice.detail.isNotBlank()) {
                Text(notice.detail, style = MaterialTheme.typography.bodySmall, color = colors.secondary)
            }
        }
    }
}
