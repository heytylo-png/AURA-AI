package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import com.example.data.model.ChatMessageEntity
import com.example.data.model.ChatSessionEntity
import com.example.data.model.MemoryEntity
import com.example.data.model.MilestoneEntity
import com.example.data.model.ProjectArtifactEntity
import com.example.data.model.ProjectEntity
import com.example.data.model.TaskActionEntity
import com.example.data.model.UserSettingsEntity

/**
 * Current schema version. Raising it REQUIRES, in the same change:
 *  1. a Migration from the previous version added to [AppDatabase.MIGRATIONS],
 *  2. the new schema file committed under app/schemas/ (written by the build),
 * DatabaseUpgradeTest fails until both exist.
 */
const val DATABASE_VERSION = 4

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
    version = DATABASE_VERSION,
    // Room writes the schema of every version to app/schemas/ (committed to git), so
    // future migrations can be written and tested against the exact previous schema.
    exportSchema = true
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

        /** Oldest version whose schema is in app/schemas/; upgrades are guaranteed from here on. */
        const val OLDEST_SUPPORTED_VERSION = 4

        /** Every schema upgrade, in order. Empty while the app is still on its first exported version. */
        val MIGRATIONS: Array<Migration> = arrayOf()

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

        private fun buildDatabase(appContext: Context): AppDatabase = build(appContext, DATABASE_NAME)

        /** The production configuration; also used by DatabaseUpgradeTest on a copy of old data. */
        internal fun build(appContext: Context, name: String): AppDatabase {
            return Room.databaseBuilder(
                appContext,
                AppDatabase::class.java,
                name
            )
                .addMigrations(*MIGRATIONS)
                // No destructive fallback: a schema change without a matching Migration must
                // fail loudly instead of silently deleting the user's memories, chats and projects.
                // Every version bump needs .addMigrations(MIGRATION_x_y) and a migration test.
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
