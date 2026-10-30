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
        // Warm up database connection on application start
        AppDatabase.getInstance(this)
    }

    override fun onTerminate() {
        super.onTerminate()
        AppDatabase.closeDatabase()
    }
}
