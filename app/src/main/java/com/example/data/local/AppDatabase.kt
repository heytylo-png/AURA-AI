package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.example.data.model.ChatMessageEntity
import com.example.data.model.ChatSessionEntity
import com.example.data.model.MemoryEntity
import com.example.data.model.MilestoneEntity
import com.example.data.model.ProjectArtifactEntity
import com.example.data.model.ProjectEntity
import com.example.data.model.TaskActionEntity
import com.example.data.model.UserSettingsEntity

@Database(
    entities = [
        MemoryEntity::class,
        ProjectEntity::class,
        MilestoneEntity::class,
        ProjectArtifactEntity::class,
        TaskActionEntity::class,
        ChatMessageEntity::class,
        ChatSessionEntity::class,
        UserSettingsEntity::class
    ],
    version = 4,
    exportSchema = false
)
@TypeConverters(RoomConverters::class)
abstract class AppDatabase : RoomDatabase() {

    /**
     * Data Access Object for persisting and querying active and contextual user memories.
     */
    abstract fun memoryDao(): MemoryDao

    /**
     * Data Access Object for persisting projects, milestones, and knowledge artifacts.
     */
    abstract fun projectDao(): ProjectDao

    /**
     * Data Access Object for managing pending AI task actions and human-in-the-loop approvals.
     */
    abstract fun taskActionDao(): TaskActionDao

    /**
     * Data Access Object for managing persistent chat conversation message history.
     */
    abstract fun chatMessageDao(): ChatMessageDao

    /**
     * Data Access Object for managing chat sessions and conversation threading.
     */
    abstract fun chatSessionDao(): ChatSessionDao

    /**
     * Data Access Object for managing user profile settings and directives.
     */
    abstract fun userSettingsDao(): UserSettingsDao

    companion object {
        const val DATABASE_NAME = "aura_ai_database"

        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * Returns the thread-safe singleton [AppDatabase] instance.
         * Ensures a single connection pool is shared across the entire application lifecycle.
         */
        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context.applicationContext).also { INSTANCE = it }
            }
        }

        private fun buildDatabase(appContext: Context): AppDatabase {
            return Room.databaseBuilder(
                appContext,
                AppDatabase::class.java,
                DATABASE_NAME
            )
                .fallbackToDestructiveMigration()
                .build()
        }

        /**
         * Creates an isolated, transient in-memory database instance primarily for testing.
         */
        fun createInMemory(context: Context): AppDatabase {
            return Room.inMemoryDatabaseBuilder(
                context.applicationContext,
                AppDatabase::class.java
            )
                .allowMainThreadQueries()
                .build()
        }

        /**
         * Closes the active database connection and resets the singleton instance.
         */
        fun closeDatabase() {
            synchronized(this) {
                INSTANCE?.let { db ->
                    if (db.isOpen) {
                        db.close()
                    }
                    INSTANCE = null
                }
            }
        }
    }
}
