package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Entity(tableName = "chat_sessions")
data class ChatSessionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val lastMessageSnippet: String = "",
    val lastModifiedAt: Long = System.currentTimeMillis(),
    val associatedProjectId: Long? = null
) {
    /**
     * Returns a compact relative time representation for the sidebar (e.g. "Just now", "5m ago", "Yesterday").
     */
    fun getFormattedTime(): String {
        val diff = System.currentTimeMillis() - lastModifiedAt
        val minutes = diff / (1000 * 60)
        val hours = minutes / 60
        val days = hours / 24

        return when {
            minutes < 1 -> "Just now"
            minutes < 60 -> "${minutes}m ago"
            hours < 24 -> "${hours}h ago"
            days < 7 -> "${days}d ago"
            else -> {
                val fmt = SimpleDateFormat("MMM d", Locale.getDefault())
                fmt.format(Date(lastModifiedAt))
            }
        }
    }
}
