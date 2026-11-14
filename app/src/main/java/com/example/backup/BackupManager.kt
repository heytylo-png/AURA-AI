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
import android.provider.DocumentsContract
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream

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

    /** A backup that was unlocked and checked, waiting for the user to confirm the restore. */
    class Staged(val database: File, val settings: String?) {
        /** Removes the unlocked copy (the user cancelled, or the restore is done). */
        fun discard() {
            database.delete()
            removeSqliteSideFiles(database)
        }
    }

    /**
     * Writes an encrypted backup of the current data to [out], piece by piece: neither the
     * database nor the backup is ever held in memory, so the size of the library doesn't matter.
     */
    fun writeBackup(out: OutputStream, passphrase: CharArray) {
        val snapshot = snapshotDatabase()
        try {
            BackupCrypto.encryptTo(out, passphrase, DATABASE_VERSION) { zip ->
                snapshot.inputStream().use { BackupCrypto.putEntry(zip, DB_ENTRY, it) }
                BackupCrypto.putEntry(zip, PREFS_ENTRY, exportPrefs().toByteArray(Charsets.UTF_8).inputStream())
            }
        } finally {
            snapshot.delete()
        }
    }

    /** Encrypted backup bytes for the current data (tests; the app writes straight to the file). */
    fun createBackup(passphrase: CharArray): ByteArray =
        ByteArrayOutputStream().also { writeBackup(it, passphrase) }.toByteArray()

    /**
     * Saves a backup to the file the user picked. The whole backup is first written (encrypted)
     * to Aura's private cache, and the picked file is only opened once that has succeeded: opening
     * it empties it, so a problem while making the backup must not cost the user an older backup
     * they chose to overwrite (audit 04, A4-01).
     */
    fun writeBackup(uri: Uri, passphrase: CharArray) {
        clearPendingSaves(app)
        val ready = File.createTempFile(PENDING_SAVE_PREFIX, ".aura", app.cacheDir)
        try {
            ready.outputStream().use { writeBackup(it, passphrase) }
            val out = app.contentResolver.openOutputStream(uri, "wt") ?: throw BackupCrypto.BackupException("Couldn't open the file to save to.")
            try {
                out.use { target -> ready.inputStream().use { it.copyTo(target) } }
            } catch (e: Throwable) {
                // The picked file is now incomplete: don't leave it behind looking like a real backup.
                runCatching { DocumentsContract.deleteDocument(app.contentResolver, uri) }
                throw e
            }
        } finally {
            ready.delete()
        }
    }

    fun inspect(uri: Uri, passphrase: CharArray): Pair<Summary, Staged> {
        val input = app.contentResolver.openInputStream(uri) ?: throw BackupCrypto.BackupException("Couldn't open that file.")
        return input.use { inspect(it, passphrase) }
    }

    fun inspect(file: ByteArray, passphrase: CharArray): Pair<Summary, Staged> = inspect(file.inputStream(), passphrase)

    /**
     * Decrypts and checks a backup without changing anything; returns what it contains. The
     * unlocked database is kept in Aura's private cache until [restore] or [Staged.discard].
     */
    fun inspect(input: InputStream, passphrase: CharArray): Pair<Summary, Staged> {
        clearUnlockedCopies(app)
        val temp = File.createTempFile(UNLOCKED_PREFIX, ".db", app.cacheDir)
        var hasDatabase = false
        var settings: String? = null
        try {
            val header = BackupCrypto.decryptTo(input, passphrase) { name, data ->
                when (name) {
                    DB_ENTRY -> { temp.outputStream().use { data.copyTo(it) }; hasDatabase = true }
                    PREFS_ENTRY -> settings = String(readSmall(data), Charsets.UTF_8)
                }
            }
            if (!hasDatabase) throw BackupCrypto.BackupException("This backup has no data in it.")
            if (header.dbVersion > DATABASE_VERSION) throw BackupCrypto.BackupException("This backup was made by a newer version of Aura. Update Aura first.")
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
            // Opening the copy to check it can leave SQLite's helper files next to it.
            removeSqliteSideFiles(temp)
            return summary to Staged(temp, settings)
        } catch (e: Throwable) {
            temp.delete()
            removeSqliteSideFiles(temp)
            throw if (e is android.database.sqlite.SQLiteException) BackupCrypto.BackupException("The data inside this backup is damaged.", e) else e
        }
    }

    private fun readSmall(data: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = data.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            if (out.size() > MAX_SETTINGS_BYTES) throw BackupCrypto.BackupException("This backup file is damaged.")
        }
        return out.toByteArray()
    }

    /**
     * Replaces this phone's data with the backup. The database is closed during the swap, so the
     * caller must restart the app afterwards ([restartApp]).
     */
    fun restore(backup: Staged) {
        if (!backup.database.exists()) throw BackupCrypto.BackupException("This backup has no data in it.")
        val live = app.getDatabasePath(AppDatabase.DATABASE_NAME)
        val staged = File(live.path + STAGED)
        val previous = File(live.path + PREVIOUS)
        live.parentFile?.mkdirs()
        staged.delete()
        // Usually a rename within Aura's storage; copy if the cache is on another volume.
        if (!backup.database.renameTo(staged)) backup.database.copyTo(staged, overwrite = true)
        backup.discard()

        AppDatabase.closeDatabase()
        // Keep the current data as a safety copy for a while (see [removeOldSafetyCopy]).
        previous.delete(); File(previous.path + "-wal").delete(); File(previous.path + "-shm").delete()
        if (live.exists() && !live.renameTo(previous)) {
            staged.delete()
            throw BackupCrypto.BackupException("Couldn't set the current data aside; nothing was changed.")
        }
        // Renaming keeps the file's old date; stamp it with now so its age counts from this restore.
        previous.setLastModified(System.currentTimeMillis())
        File(live.path + "-wal").renameTo(File(previous.path + "-wal"))
        File(live.path + "-shm").delete()
        File(live.path + "-journal").delete()
        if (!staged.renameTo(live)) {
            previous.renameTo(live)
            File(previous.path + "-wal").renameTo(File(live.path + "-wal"))
            staged.delete()
            throw BackupCrypto.BackupException("Couldn't put the backup in place; your data was left as it was.")
        }
        backup.settings?.let { importPrefs(it) }
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
        private const val UNLOCKED_PREFIX = "aura_restore_check"
        private const val MAX_SETTINGS_BYTES = 1024 * 1024
        private const val PENDING_SAVE_PREFIX = "aura_backup_ready"

        /** Removes encrypted backups left in the cache by a save that was interrupted. */
        fun clearPendingSaves(context: Context) {
            context.cacheDir.listFiles { f -> f.name.startsWith(PENDING_SAVE_PREFIX) }?.forEach { it.delete() }
        }

        private fun removeSqliteSideFiles(database: File) {
            for (suffix in listOf("-journal", "-wal", "-shm")) File(database.path + suffix).delete()
        }

        /**
         * Removes unlocked (unencrypted) backup copies left in the cache, e.g. when the app was
         * closed while the "Replace the data on this phone?" question was showing.
         */
        fun clearUnlockedCopies(context: Context) {
            context.cacheDir.listFiles { f -> f.name.startsWith(UNLOCKED_PREFIX) }?.forEach { it.delete() }
        }
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
