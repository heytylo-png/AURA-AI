package com.example.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class MemoryCategory(val displayName: String, val iconName: String) {
    PREFERENCE("Preference", "tune"),
    FACT("Personal Fact", "person"),
    GOAL("Goal & Milestone", "flag"),
    CONTEXT("Work Context", "work"),
    IDENTITY_RULE("Behavior Rule", "psychology")
}

@Entity(
    tableName = "memories",
    indices = [
        Index(value = ["category"]),
        Index(value = ["isActive", "importance"])
    ]
)
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val content: String,
    val category: MemoryCategory,
    val importance: Int = 3, // 1 (low) to 5 (critical)
    val isActive: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val lastAccessedAt: Long = System.currentTimeMillis()
)
