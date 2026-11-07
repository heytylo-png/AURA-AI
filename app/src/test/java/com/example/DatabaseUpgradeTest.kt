package com.example

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.local.DATABASE_VERSION
import com.example.data.model.AiModelUsed
import com.example.data.model.MemoryCategory
import com.example.data.model.Sender
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Your memories, chats and projects must survive every app update.
 *
 * For every schema version from [AppDatabase.OLDEST_SUPPORTED_VERSION] up to the current one,
 * this builds a database exactly as that version left it (from the committed schema file),
 * fills it with data, then opens it with the app's real configuration. Room runs the
 * migrations and checks the result against the current schema; the data must still be there.
 *
 * Raising DATABASE_VERSION without a migration or without the new schema file fails here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DatabaseUpgradeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dbName = "upgrade-test.db"

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    private fun schemaFile(version: Int): File {
        val relative = "schemas/${AppDatabase::class.java.name}/$version.json"
        return listOf(File(relative), File("app/$relative")).firstOrNull { it.exists() }
            ?: throw AssertionError("Missing app/$relative. Build once so Room exports it, then commit it.")
    }

    @Test
    fun everyVersionHasASchemaFileAndAMigration() {
        for (v in AppDatabase.OLDEST_SUPPORTED_VERSION..DATABASE_VERSION) {
            val schema = JSONObject(schemaFile(v).readText()).getJSONObject("database")
            assertEquals("schema file $v.json", v, schema.getInt("version"))
        }
        for (v in AppDatabase.OLDEST_SUPPORTED_VERSION until DATABASE_VERSION) {
            assertTrue(
                "No migration from version $v to ${v + 1} in AppDatabase.MIGRATIONS",
                AppDatabase.MIGRATIONS.any { it.startVersion == v && it.endVersion == v + 1 }
            )
        }
    }

    @Test
    fun dataSurvivesUpgradeFromEverySupportedVersion() = runBlocking {
        for (v in AppDatabase.OLDEST_SUPPORTED_VERSION..DATABASE_VERSION) {
            context.deleteDatabase(dbName)
            createDatabaseAtVersion(v)

            val db = AppDatabase.build(context, dbName)
            try {
                val memory = db.memoryDao().getMemoryByIdSnapshot(1)
                assertNotNull("memory lost when upgrading from version $v", memory)
                assertEquals("I prefer short answers", memory!!.content)
                assertEquals(MemoryCategory.PREFERENCE, memory.category)
                if (v < 5) {
                    // Memories from before origins were recorded are labelled as such, never guessed.
                    org.junit.Assert.assertNull(memory.source)
                    org.junit.Assert.assertNull(memory.updatedAt)
                }

                val session = db.chatSessionDao().getSessionById(1)
                assertEquals("Weekend plans", session?.title)

                val messages = db.chatMessageDao().getMessagesForSession(1).first()
                assertEquals(listOf("Hi Starai", "Hello!"), messages.map { it.text })
                assertEquals(AiModelUsed.CLOUD, messages[1].modelUsed)
            } finally {
                db.close()
            }
        }
    }

    /** Recreates the database file exactly as schema [version] defines it, with sample rows. */
    private fun createDatabaseAtVersion(version: Int) {
        val schema = JSONObject(schemaFile(version).readText()).getJSONObject("database")
        val file = context.getDatabasePath(dbName).also { it.parentFile?.mkdirs() }
        val sql = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                sql.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices")
                if (indices != null) for (j in 0 until indices.length()) {
                    sql.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) sql.execSQL(setup.getString(i))

            // Sample data. If a later version renames these columns, write that version's rows here by `version`.
            sql.execSQL(
                "INSERT INTO memories (id, content, category, importance, isActive, createdAt, lastAccessedAt) " +
                    "VALUES (1, 'I prefer short answers', '${MemoryCategory.PREFERENCE.name}', 3, 1, 1000, 1000)"
            )
            sql.execSQL(
                "INSERT INTO chat_sessions (id, title, lastMessageSnippet, lastModifiedAt, associatedProjectId) " +
                    "VALUES (1, 'Weekend plans', 'Hello!', 2000, NULL)"
            )
            sql.execSQL(
                "INSERT INTO chat_messages (id, sender, text, timestamp, modelUsed, reasoningExplanation, " +
                    "associatedProjectId, proposedActionId, sessionId) VALUES " +
                    "(1, '${Sender.USER.name}', 'Hi Starai', 1500, NULL, NULL, NULL, NULL, 1), " +
                    "(2, '${Sender.AI.name}', 'Hello!', 1600, '${AiModelUsed.CLOUD.name}', NULL, NULL, NULL, 1)"
            )
            sql.version = version
        } finally {
            sql.close()
        }
    }
}
