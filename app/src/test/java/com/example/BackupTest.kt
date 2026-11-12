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

    private fun expectAnyFailure(what: String, block: () -> Unit) {
        try { block(); fail("expected a failure: $what") } catch (e: BackupCrypto.BackupException) { /* refused, as it should be */ }
    }

    private fun bytes(n: Int) = ByteArray(n) { (it * 31 + 7).toByte() }

    @Test fun backupsAreWrittenInPiecesAndRoundTripAtEverySize() {
        val chunk = 1024
        // Incompressible-ish data around the piece boundaries, and one spanning many pieces.
        for (size in listOf(0, 1, chunk - 1, chunk, chunk + 1, 3 * chunk, 50_000)) {
            val data = java.util.Random(size.toLong()).let { r -> ByteArray(size).also(r::nextBytes) }
            val file = BackupCrypto.encrypt(mapOf("database.sqlite" to data, "settings.json" to "{}".toByteArray()), pass, 7, iterations = fast, chunkSize = chunk)
            val header = BackupCrypto.readHeader(file)
            assertEquals(2, header.format)
            assertEquals(chunk, header.chunkSize)
            val (_, out) = BackupCrypto.decrypt(file, pass)
            assertArrayEquals("size $size", data, out["database.sqlite"])
            assertArrayEquals("{}".toByteArray(), out["settings.json"])
        }
    }

    @Test fun piecesCannotBeCutOffDroppedSwappedOrRepeated() {
        val chunk = 1024
        val sealed = chunk + 16
        val data = java.util.Random(1).let { r -> ByteArray(10 * chunk).also(r::nextBytes) }
        val file = BackupCrypto.encrypt(mapOf("a.bin" to data), pass, 7, iterations = fast, chunkSize = chunk)
        val start = 8 + 4 + java.nio.ByteBuffer.wrap(file, 8, 4).int
        assertTrue("several pieces", (file.size - start) > 5 * sealed)
        fun piece(i: Int) = file.copyOfRange(start + i * sealed, minOf(file.size, start + (i + 1) * sealed))
        val head = file.copyOfRange(0, start)
        val pieces = (0 until (file.size - start + sealed - 1) / sealed).map(::piece)
        fun build(list: List<ByteArray>) = list.fold(head) { acc, p -> acc + p }

        assertArrayEquals(file, build(pieces))
        expectAnyFailure("cut off after a whole piece") { BackupCrypto.decrypt(build(pieces.dropLast(1)), pass) }
        expectAnyFailure("cut off after two pieces") { BackupCrypto.decrypt(build(pieces.take(2)), pass) }
        expectAnyFailure("cut off mid-piece") { BackupCrypto.decrypt(file.copyOf(file.size - 5), pass) }
        expectAnyFailure("header only") { BackupCrypto.decrypt(head, pass) }
        expectAnyFailure("a piece dropped") { BackupCrypto.decrypt(build(pieces.filterIndexed { i, _ -> i != 2 }), pass) }
        expectAnyFailure("two pieces swapped") { BackupCrypto.decrypt(build(pieces.toMutableList().also { val t = it[1]; it[1] = it[2]; it[2] = t }), pass) }
        expectAnyFailure("a piece repeated") { BackupCrypto.decrypt(build(pieces.take(3) + pieces.drop(2)), pass) }
        expectAnyFailure("extra data added") { BackupCrypto.decrypt(file + ByteArray(40), pass) }
        // A change in the middle of a large file is caught too.
        val flipped = file.copyOf().also { it[start + 4 * sealed + 100] = (it[start + 4 * sealed + 100].toInt() xor 1).toByte() }
        expectFailure("Wrong passphrase") { BackupCrypto.decrypt(flipped, pass) }
    }

    @Test fun backupsMadeBeforeTheNewFormatStillOpen() {
        val entries = mapOf("database.sqlite" to bytes(5000), "settings.json" to "{}".toByteArray())
        val old = BackupCrypto.encryptFormat1(entries, pass, 7, now = 42, iterations = fast)
        assertEquals(1, BackupCrypto.readHeader(old).format)
        val (header, out) = BackupCrypto.decrypt(old, pass)
        assertEquals(42L, header.createdAt)
        assertArrayEquals(bytes(5000), out["database.sqlite"])
        expectFailure("Wrong passphrase") { BackupCrypto.decrypt(old, "wrong passphrase".toCharArray()) }
        val flipped = old.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        expectFailure("Wrong passphrase") { BackupCrypto.decrypt(flipped, pass) }
    }

    @Test fun cancellingARestoreRemovesTheUnlockedCopy() = runBlocking {
        val db = AppDatabase.getInstance(app)
        db.todoDao().insert(TodoEntity(title = "Pay rent"))
        val manager = BackupManager(app)
        val backup = manager.createBackup(pass)
        fun unlocked() = app.cacheDir.listFiles { f -> f.name.startsWith("aura_restore_check") }!!.size

        val (_, staged) = manager.inspect(backup, pass)
        assertEquals(1, unlocked())
        staged.discard()
        assertEquals(0, unlocked())

        // A wrong passphrase or a damaged file leaves nothing behind either.
        expectFailure("Wrong passphrase") { manager.inspect(backup, "wrong passphrase".toCharArray()) }
        expectAnyFailure("cut-off file") { manager.inspect(backup.copyOf(backup.size / 2), pass) }
        assertEquals(0, unlocked())

        // Left over from a closed app: cleared on the next start.
        manager.inspect(backup, pass)
        assertEquals(1, unlocked())
        BackupManager.clearUnlockedCopies(app)
        assertEquals(0, unlocked())
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
