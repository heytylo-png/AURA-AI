package com.example.backup

import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.util.Log
import com.example.data.local.AppDatabase
import com.example.data.local.DATABASE_VERSION
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Makes and restores encrypted backups of everything Aura keeps on the phone: the database
 * (chats, memories, projects, to-dos, approvals) and Aura's settings. Not included: the relay
 * sign-in (a restored phone gets a new access ID) and Android's notification/microphone permissions.
 *
 * Restoring REPLACES the data on this phone. It is done so an interruption can't leave a half-
 * restored database: the new file is staged first, then swapped in with renames, and
 * [finishInterruptedRestore] completes or undoes a swap on the next start.
 */
class BackupManager(private val context: Context) {

    data class Summary(val createdAt: Long, val dbVersion: Int, val memories: Int, val chats: Int, val todos: Int, val projects: Int)

    private val app = context.applicationContext

    /** Encrypted backup bytes for the current data. */
    fun createBackup(passphrase: CharArray): ByteArray {
        val snapshot = snapshotDatabase()
        try {
            val entries = linkedMapOf(DB_ENTRY to snapshot.readBytes(), PREFS_ENTRY to exportPrefs().toByteArray(Charsets.UTF_8))
            return BackupCrypto.encrypt(entries, passphrase, DATABASE_VERSION)
        } finally {
            snapshot.delete()
        }
    }

    fun writeBackup(uri: Uri, passphrase: CharArray) {
        val bytes = createBackup(passphrase)
        val out = app.contentResolver.openOutputStream(uri, "wt") ?: throw BackupCrypto.BackupException("Couldn't open the file to save to.")
        out.use { it.write(bytes) }
    }

    fun readFile(uri: Uri): ByteArray {
        val input = app.contentResolver.openInputStream(uri) ?: throw BackupCrypto.BackupException("Couldn't open that file.")
        return input.use {
            val bytes = it.readBytes()
            if (bytes.size > BackupCrypto.MAX_FILE_BYTES) throw BackupCrypto.BackupException("This file is too large to be an Aura backup.")
            bytes
        }
    }

    /** Decrypts and checks a backup without changing anything; returns what it contains. */
    fun inspect(file: ByteArray, passphrase: CharArray): Pair<Summary, Map<String, ByteArray>> {
        val (header, entries) = BackupCrypto.decrypt(file, passphrase)
        val db = entries[DB_ENTRY] ?: throw BackupCrypto.BackupException("This backup has no data in it.")
        if (header.dbVersion > DATABASE_VERSION) throw BackupCrypto.BackupException("This backup was made by a newer version of Aura. Update Aura first.")
        val temp = File.createTempFile("aura_restore_check", ".db", app.cacheDir)
        try {
            temp.writeBytes(db)
            val summary = SQLiteDatabase.openDatabase(temp.path, null, SQLiteDatabase.OPEN_READONLY).use { sql ->
                val ok = sql.rawQuery("PRAGMA integrity_check", null).use { c -> c.moveToFirst() && c.getString(0) == "ok" }
                if (!ok) throw BackupCrypto.BackupException("The data inside this backup is damaged.")
                val version = sql.version
                if (version < 1 || version > DATABASE_VERSION || version != header.dbVersion) {
                    throw BackupCrypto.BackupException("This backup's data doesn't match its label.")
                }
                fun count(table: String) = runCatching {
                    sql.rawQuery("SELECT COUNT(*) FROM $table", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
                }.getOrDefault(0)
                if (!hasTable(sql, "memories")) throw BackupCrypto.BackupException("This backup doesn't contain Aura data.")
                Summary(header.createdAt, version, count("memories"), count("chat_sessions"), count("todos"), count("projects"))
            }
            return summary to entries
        } finally {
            temp.delete()
            File(temp.path + "-journal").delete()
        }
    }

    /**
     * Replaces this phone's data with the backup. The database is closed during the swap, so the
     * caller must restart the app afterwards ([restartApp]).
     */
    fun restore(entries: Map<String, ByteArray>) {
        val db = entries[DB_ENTRY] ?: throw BackupCrypto.BackupException("This backup has no data in it.")
        val live = app.getDatabasePath(AppDatabase.DATABASE_NAME)
        val staged = File(live.path + STAGED)
        val previous = File(live.path + PREVIOUS)
        live.parentFile?.mkdirs()
        staged.writeBytes(db)

        AppDatabase.closeDatabase()
        // Keep the current data as a safety copy for a while (see [removeOldSafetyCopy]).
        previous.delete(); File(previous.path + "-wal").delete(); File(previous.path + "-shm").delete()
        if (live.exists() && !live.renameTo(previous)) throw BackupCrypto.BackupException("Couldn't set the current data aside; nothing was changed.")
        // Renaming keeps the file's old date; stamp it with now so its age counts from this restore.
        previous.setLastModified(System.currentTimeMillis())
        File(live.path + "-wal").renameTo(File(previous.path + "-wal"))
        File(live.path + "-shm").delete()
        File(live.path + "-journal").delete()
        if (!staged.renameTo(live)) {
            previous.renameTo(live)
            File(previous.path + "-wal").renameTo(File(live.path + "-wal"))
            throw BackupCrypto.BackupException("Couldn't put the backup in place; your data was left as it was.")
        }
        entries[PREFS_ENTRY]?.let { importPrefs(String(it, Charsets.UTF_8)) }
    }

    /** A consistent copy of the live database file. */
    private fun snapshotDatabase(): File {
        val room = AppDatabase.getInstance(app)
        val sql = room.openHelper.writableDatabase
        // Fold the write-ahead log into the main file, then copy while writers are held off.
        sql.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
        val live = app.getDatabasePath(AppDatabase.DATABASE_NAME)
        val copy = File.createTempFile("aura_backup", ".db", app.cacheDir)
        sql.beginTransaction()
        try {
            live.copyTo(copy, overwrite = true)
            File(live.path + "-wal").takeIf { it.exists() && it.length() > 0 }?.let { wal ->
                // Something was written between the checkpoint and the copy; include it.
                File(copy.path + "-wal").also { wal.copyTo(it, overwrite = true) }
            }
        } finally {
            sql.endTransaction()
        }
        val copyWal = File(copy.path + "-wal")
        if (copyWal.exists()) {
            // Opening the copy read-write applies its WAL; closing folds it into the main file.
            SQLiteDatabase.openDatabase(copy.path, null, SQLiteDatabase.OPEN_READWRITE).use { it.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { c -> c.moveToFirst() } }
            copyWal.delete(); File(copy.path + "-shm").delete()
        }
        return copy
    }

    private fun hasTable(sql: SQLiteDatabase, name: String) =
        sql.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", arrayOf(name)).use { it.moveToFirst() }

    fun exportPrefs(): String {
        val root = JSONObject()
        for (file in PREFS_FILES) {
            val values = JSONObject()
            for ((key, value) in app.getSharedPreferences(file, Context.MODE_PRIVATE).all) {
                if (file == REMINDER_PREFS && key == "last_handled_at") continue
                val typed = when (value) {
                    is Boolean -> JSONObject().put("t", "b").put("v", value)
                    is Int -> JSONObject().put("t", "i").put("v", value)
                    is Long -> JSONObject().put("t", "l").put("v", value)
                    is Float -> JSONObject().put("t", "f").put("v", value.toDouble())
                    is String -> JSONObject().put("t", "s").put("v", value)
                    is Set<*> -> JSONObject().put("t", "ss").put("v", JSONArray(value.filterIsInstance<String>()))
                    else -> null
                } ?: continue
                values.put(key, typed)
            }
            root.put(file, values)
        }
        return root.toString()
    }

    /** Replaces Aura's settings with the backup's. Unknown files and types are ignored. */
    fun importPrefs(json: String) {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return
        for (file in PREFS_FILES) {
            val values = root.optJSONObject(file) ?: continue
            val editor = app.getSharedPreferences(file, Context.MODE_PRIVATE).edit().clear()
            for (key in values.keys()) {
                val typed = values.optJSONObject(key) ?: continue
                when (typed.optString("t")) {
                    "b" -> editor.putBoolean(key, typed.optBoolean("v"))
                    "i" -> editor.putInt(key, typed.optInt("v"))
                    "l" -> editor.putLong(key, typed.optLong("v"))
                    "f" -> editor.putFloat(key, typed.optDouble("v").toFloat())
                    "s" -> editor.putString(key, typed.optString("v"))
                    "ss" -> typed.optJSONArray("v")?.let { arr -> editor.putStringSet(key, (0 until arr.length()).map { arr.optString(it) }.toSet()) }
                }
            }
            if (file == REMINDER_PREFS) editor.putLong("last_handled_at", System.currentTimeMillis())
            editor.commit()
        }
    }

    companion object {
        const val DB_ENTRY = "database.sqlite"
        const val PREFS_ENTRY = "settings.json"
        private const val STAGED = ".restore"
        private const val PREVIOUS = ".before-restore"
        private const val REMINDER_PREFS = "aura_reminders"
        val PREFS_FILES = listOf("aura_identity_prefs", "aura_memory_prefs", "aura_privacy_prefs", REMINDER_PREFS)

        /** Call before the database is first opened: completes or undoes an interrupted swap. */
        fun finishInterruptedRestore(context: Context) {
            val live = context.getDatabasePath(AppDatabase.DATABASE_NAME)
            val staged = File(live.path + STAGED)
            val previous = File(live.path + PREVIOUS)
            if (!live.exists()) {
                when {
                    staged.exists() -> staged.renameTo(live)
                    previous.exists() -> previous.renameTo(live)
                }
            } else if (staged.exists()) {
                staged.delete()
            }
        }

        /** How long the pre-restore safety copy (unencrypted, in Aura's private storage) is kept. */
        const val SAFETY_COPY_DAYS = 7

        /**
         * Removes the safety copy once it is older than [SAFETY_COPY_DAYS], so data replaced by a
         * restore (including things deleted since) doesn't stay on the phone forever. Call after
         * [finishInterruptedRestore]; does nothing while the copy might still be needed to recover.
         */
        fun removeOldSafetyCopy(context: Context, now: Long = System.currentTimeMillis()) {
            val live = context.getDatabasePath(AppDatabase.DATABASE_NAME)
            val previous = File(live.path + PREVIOUS)
            if (!live.exists() || !previous.exists()) return
            if (now - previous.lastModified() < SAFETY_COPY_DAYS * 86_400_000L) return
            previous.delete()
            File(previous.path + "-wal").delete()
            File(previous.path + "-shm").delete()
        }

        /** Relaunches Aura so every screen reopens the restored data. */
        fun restartApp(context: Context) {
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
            context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            Log.i("AuraBackup", "Restarting after restore")
            Runtime.getRuntime().exit(0)
        }
    }
}
