package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class ActionType(val title: String) {
    CREATE_PROJECT("Create Project"),
    ADD_MILESTONE("Add Milestone"),
    SAVE_MEMORY("Store New Memory"),
    CREATE_TODO("Create Todo Task"),
    GENERATE_SUMMARY("Generate Project Summary"),
    UPDATE_PREFERENCE("Update Preference")
}

enum class ActionStatus {
    PROPOSED, // Waiting for user approval
    APPROVED_EXECUTED, // User approved and AI carried it out
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
