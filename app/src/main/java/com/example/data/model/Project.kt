package com.example.data.model

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

enum class ProjectStatus(val label: String) {
    PLANNING("Planning"),
    IN_PROGRESS("In Progress"),
    REVIEW("In Review"),
    COMPLETED("Completed"),
    PAUSED("Paused")
}

@Entity(
    tableName = "projects",
    indices = [
        Index(value = ["status"]),
        Index(value = ["createdAt"])
    ]
)
data class ProjectEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val description: String,
    val category: String = "General",
    val status: ProjectStatus = ProjectStatus.PLANNING,
    val colorHex: String = "#00F2FE",
    val targetDateMillis: Long = System.currentTimeMillis() + 14L * 24 * 60 * 60 * 1000,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "project_milestones",
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["projectId"])
    ]
)
data class MilestoneEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val projectId: Long,
    val title: String,
    val isCompleted: Boolean = false,
    val targetDateMillis: Long = 0
)

@Entity(
    tableName = "project_artifacts",
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["projectId"])
    ]
)
data class ProjectArtifactEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val projectId: Long,
    val title: String,
    val content: String,
    val type: String = "NOTE", // NOTE, CODE, BRAINSTORM, OUTLINE
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * Composite relationship modeling a Project along with all its milestones
 * and attached document artifacts in an atomic transaction.
 */
data class ProjectWithDetails(
    @Embedded val project: ProjectEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "projectId"
    )
    val milestones: List<MilestoneEntity> = emptyList(),
    @Relation(
        parentColumn = "id",
        entityColumn = "projectId"
    )
    val artifacts: List<ProjectArtifactEntity> = emptyList()
)
