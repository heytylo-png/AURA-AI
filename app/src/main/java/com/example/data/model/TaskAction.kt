package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class ActionType(val title: String) {
    // Short labels: the card shows this as its badge, so it names the kind of change, nothing more.
    CREATE_PROJECT("Project"),
    ADD_MILESTONE("Milestone"),
    SAVE_MEMORY("Memory"),
    CREATE_TODO("To-do"),
    GENERATE_SUMMARY("Project summary"),
    UPDATE_PREFERENCE("Preference")
}

// Stored by name (TEXT column), so adding values needs no schema migration.
enum class ActionStatus {
    PROPOSED, // Waiting for user approval
    EXECUTING, // Approved; the operation is running
    APPROVED_EXECUTED, // Approved and the operation completed successfully
    FAILED, // Approved but the operation did not complete; nothing it would have written was kept
    DISMISSED // User rejected
}

@Entity(tableName = "task_actions")
data class TaskActionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val description: String,
    val actionType: ActionType,
    val reasoning: String, // Why the AI recommends this
    val payloadJson: String = "", // JSON string containing action parameters
    val status: ActionStatus = ActionStatus.PROPOSED,
    val associatedProjectId: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val executedAt: Long? = null
)
