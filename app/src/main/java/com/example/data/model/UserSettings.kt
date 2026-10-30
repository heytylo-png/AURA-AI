package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persisted user profile settings stored in Room Database.
 * Used to personalize AI prompts and conversational tone.
 */
@Entity(tableName = "user_settings")
data class UserSettingsEntity(
    @PrimaryKey
    val id: Int = 1,
    val userName: String = "",
    val primaryInterest: String = ""
)
