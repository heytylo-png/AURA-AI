package com.example

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.backup.BackupCrypto
import com.example.backup.BackupManager
import com.example.data.local.AppDatabase
import com.example.data.local.DATABASE_VERSION
import com.example.data.model.MemoryCategory
import com.example.data.model.MemoryEntity
import com.example.data.model.TodoEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Backups must round-trip exactly, refuse wrong passphrases and tampering, and restore real data. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BackupTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val pass = "correct horse".toCharArray()
    private val fast = 10_000 // test speed; production uses 210,000

    @After fun close() = AppDatabase.closeDatabase()

    private fun expectFailure(message: String, block: () -> Unit) {
        try { block(); fail("expected: $message") } catch (e: BackupCrypto.BackupException) {
            assertTrue("got: ${e.message}", e.message!!.contains(message))
        }
    }

    @Test fun cryptoRoundTripsAndChecksPassphrase() {
        val entries = mapOf("a.bin" to byteArrayOf(1, 2, 3), "settings.json" to "{}".toByteArray())
        val file = BackupCrypto.encrypt(entries, pass, 7, now = 42, iterations = fast)
        val (header, out) = BackupCrypto.decrypt(file, pass)
        assertEquals(42L, header.createdAt)
        assertEquals(7, header.dbVersion)
        assertArrayEquals(byteArrayOf(1, 2, 3), out["a.bin"])
        assertEquals(setOf("a.bin", "settings.json"), out.keys)
        expectFailure("Wrong passphrase") { BackupCrypto.decrypt(file, "wrong passphrase".toCharArray()) }
        expectFailure("at least 8") { BackupCrypto.encrypt(entries, "short".toCharArray(), 7) }
    }

    @Test fun tamperingAndJunkAreRejected() {
        val file = BackupCrypto.encrypt(mapOf("a" to byteArrayOf(9)), pass, 7, iterations = fast)
        // Flip one ciphertext byte.
        val flipped = file.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        expectFailure("Wrong passphrase") { BackupCrypto.decrypt(flipped, pass) }
        // Edit the header (it is authenticated): change createdAt digits.
        val text = String(file, Charsets.ISO_8859_1)
        val edited = text.replaceFirst("\"dbVersion\":7", "\"dbVersion\":6").toByteArray(Charsets.ISO_8859_1)
        expectFailure("Wrong passphrase") { BackupCrypto.decrypt(edited, pass) }
        expectFailure("isn't an Aura backup") { BackupCrypto.decrypt("hello world, not a backup".toByteArray(), pass) }
        // Paths inside the archive are refused.
        expectFailure("damaged") { BackupCrypto.unzip(BackupCrypto.zip(mapOf("../evil" to byteArrayOf(1)))) }
    }

    @Test fun saveThenRestoreReplacesDataAndSettings() = runBlocking {
        val db = AppDatabase.getInstance(app)
        db.memoryDao().insertMemory(MemoryEntity(content = "Likes green tea", category = MemoryCategory.values().first()))
        db.todoDao().insert(TodoEntity(title = "Pay rent"))
        app.getSharedPreferences("aura_privacy_prefs", Context.MODE_PRIVATE).edit().putBoolean("share_open_todos", true).commit()

        val manager = BackupManager(app)
        val backup = manager.createBackup(pass)

        // Change everything after the backup.
        db.todoDao().insert(TodoEntity(title = "Added later"))
        app.getSharedPreferences("aura_privacy_prefs", Context.MODE_PRIVATE).edit().putBoolean("share_open_todos", false).commit()

        val (summary, entries) = manager.inspect(backup, pass)
        assertEquals(1, summary.memories)
        assertEquals(1, summary.todos)
        assertEquals(DATABASE_VERSION, summary.dbVersion)

        manager.restore(entries)
        val restored = AppDatabase.getInstance(app)
        assertEquals(listOf("Pay rent"), restored.todoDao().getOpen().map { it.title })
        assertTrue(app.getSharedPreferences("aura_privacy_prefs", Context.MODE_PRIVATE).getBoolean("share_open_todos", false))
        val live = app.getDatabasePath(AppDatabase.DATABASE_NAME)
        assertTrue(File(live.path + ".before-restore").exists())
        assertFalse(File(live.path + ".restore").exists())
    }

    @Test fun interruptedRestoreIsCompletedOnStart() {
        val live = app.getDatabasePath(AppDatabase.DATABASE_NAME)
        live.parentFile!!.mkdirs()
        live.delete()
        File(live.path + ".restore").writeBytes(byteArrayOf(1))
        BackupManager.finishInterruptedRestore(app)
        assertTrue(live.exists())
        assertFalse(File(live.path + ".restore").exists())
        live.delete()
    }
}
