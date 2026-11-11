package com.example

import android.app.Application
import com.example.data.local.AppDatabase

/**
 * Application class for Aura AI.
 * Manages app-level singleton instances and initializes the local RoomDatabase
 * connection to user memory and project persistence storage.
 */
class AuraApplication : Application() {

    /**
     * Lazily initialized singleton database instance managing connections to
     * local memory storage, conversations, and project library.
     */
    val database: AppDatabase by lazy {
        AppDatabase.getInstance(this)
    }

    override fun onCreate() {
        super.onCreate()
        // Complete (or undo) a backup restore that was interrupted, before the database opens.
        com.example.backup.BackupManager.finishInterruptedRestore(this)
        com.example.backup.BackupManager.removeOldSafetyCopy(this)
        // Warm up database connection on application start
        AppDatabase.getInstance(this)
    }

    override fun onTerminate() {
        super.onTerminate()
        AppDatabase.closeDatabase()
    }
}
