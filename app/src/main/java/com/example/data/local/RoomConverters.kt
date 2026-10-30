package com.example.data.local

import androidx.room.TypeConverter
import com.example.data.model.MemoryCategory
import com.example.data.model.ProjectStatus

/**
 * Room TypeConverters for Aura's enum fields ensuring safe persistence and migration.
 */
class RoomConverters {

    @TypeConverter
    fun fromMemoryCategory(category: MemoryCategory?): String? {
        return category?.name
    }

    @TypeConverter
    fun toMemoryCategory(value: String?): MemoryCategory {
        if (value.isNullOrBlank()) return MemoryCategory.FACT
        return try {
            MemoryCategory.valueOf(value)
        } catch (e: Exception) {
            MemoryCategory.FACT
        }
    }

    @TypeConverter
    fun fromProjectStatus(status: ProjectStatus?): String? {
        return status?.name
    }

    @TypeConverter
    fun toProjectStatus(value: String?): ProjectStatus {
        if (value.isNullOrBlank()) return ProjectStatus.PLANNING
        return try {
            ProjectStatus.valueOf(value)
        } catch (e: Exception) {
            ProjectStatus.PLANNING
        }
    }

    @TypeConverter
    fun fromSender(sender: com.example.data.model.Sender?): String? {
        return sender?.name
    }

    @TypeConverter
    fun toSender(value: String?): com.example.data.model.Sender {
        if (value.isNullOrBlank()) return com.example.data.model.Sender.USER
        return try {
            com.example.data.model.Sender.valueOf(value)
        } catch (e: Exception) {
            com.example.data.model.Sender.USER
        }
    }

    @TypeConverter
    fun fromAiModelUsed(model: com.example.data.model.AiModelUsed?): String? {
        return model?.name
    }

    @TypeConverter
    fun toAiModelUsed(value: String?): com.example.data.model.AiModelUsed? {
        if (value.isNullOrBlank()) return null
        return try {
            com.example.data.model.AiModelUsed.valueOf(value)
        } catch (e: Exception) {
            null
        }
    }

    @TypeConverter
    fun fromActionType(type: com.example.data.model.ActionType?): String? {
        return type?.name
    }

    @TypeConverter
    fun toActionType(value: String?): com.example.data.model.ActionType {
        if (value.isNullOrBlank()) return com.example.data.model.ActionType.SAVE_MEMORY
        return try {
            com.example.data.model.ActionType.valueOf(value)
        } catch (e: Exception) {
            com.example.data.model.ActionType.SAVE_MEMORY
        }
    }

    @TypeConverter
    fun fromActionStatus(status: com.example.data.model.ActionStatus?): String? {
        return status?.name
    }

    @TypeConverter
    fun toActionStatus(value: String?): com.example.data.model.ActionStatus {
        if (value.isNullOrBlank()) return com.example.data.model.ActionStatus.PROPOSED
        return try {
            com.example.data.model.ActionStatus.valueOf(value)
        } catch (e: Exception) {
            com.example.data.model.ActionStatus.PROPOSED
        }
    }
}
