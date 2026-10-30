package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class Sender {
    USER,
    AI
}

enum class AiModelUsed(val label: String, val badgeIcon: String) {
    LOCAL("Local Offline Model", "bolt"),
    CLOUD("Gemini 3.8 Flash (Cloud)", "cloud")
}

@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val sender: Sender,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val modelUsed: AiModelUsed? = null,
    val reasoningExplanation: String? = null,
    val associatedProjectId: Long? = null,
    val proposedActionId: Long? = null, // Link to task action if AI proposed one in this turn
    val sessionId: Long = 1L
) {
    /**
     * Returns a human-readable timestamp, e.g. "10:45 AM" or "Yesterday, 3:15 PM" or "Oct 24, 10:45 AM".
     */
    fun getFormattedTime(): String {
        return try {
            val messageDate = java.util.Date(timestamp)
            val now = java.util.Calendar.getInstance()
            val msgCal = java.util.Calendar.getInstance().apply { time = messageDate }

            val timeFormat = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
            val formattedTime = timeFormat.format(messageDate)

            val isSameYear = now.get(java.util.Calendar.YEAR) == msgCal.get(java.util.Calendar.YEAR)
            val dayDiff = now.get(java.util.Calendar.DAY_OF_YEAR) - msgCal.get(java.util.Calendar.DAY_OF_YEAR)

            if (isSameYear && dayDiff == 0) {
                formattedTime
            } else if (isSameYear && dayDiff == 1) {
                "Yesterday, $formattedTime"
            } else {
                val dateFormat = java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.getDefault())
                dateFormat.format(messageDate)
            }
        } catch (e: Exception) {
            "Just now"
        }
    }
}
