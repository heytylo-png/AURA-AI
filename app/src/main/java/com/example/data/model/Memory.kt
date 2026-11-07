package com.example.data.model

import androidx.room.ColumnInfo
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
    val lastAccessedAt: Long = System.currentTimeMillis(),
    /** Where this memory came from; null = saved by a version of Aura before this was recorded. */
    val source: MemorySource? = null,
    /** When the user last edited it; null = never edited. */
    val updatedAt: Long? = null,
    /** The user marked this memory as OK to send to Claude (only used when sharing is on in Settings). */
    @ColumnInfo(defaultValue = "0")
    val shareWithCloud: Boolean = false
)

/** How a memory got into Aura. Shown on each memory so the user can judge it. */
enum class MemorySource(val label: String) {
    TYPED("Added by you"),
    CHAT_APPROVED("From chat, approved by you")
}

/** Label for a memory's origin, including ones saved before origins were recorded. */
val MemoryEntity.sourceLabel: String
    get() = source?.label ?: "Saved by an older version of Aura"

/**
 * Applies a user's edit. Returns null when the new text is blank (nothing to save).
 * Keeps id, creation time, origin and on/off state; records the edit time.
 */
fun MemoryEntity.edited(content: String, category: MemoryCategory, importance: Int, now: Long): MemoryEntity? {
    val text = content.trim()
    if (text.isEmpty()) return null
    return copy(content = text, category = category, importance = importance.coerceIn(1, 5), updatedAt = now)
}
