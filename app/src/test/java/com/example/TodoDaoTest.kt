package com.example

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.model.TodoEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Recently deleted works: restorable, removable for good, purged after 30 days, never by accident. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TodoDaoTest {
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun visibleDeletedAndCounts() = runBlocking {
        val dao = db.todoDao()
        dao.insert(TodoEntity(title = "open"))
        dao.insert(TodoEntity(title = "done", done = true, doneAt = 1))
        dao.insert(TodoEntity(title = "gone", deletedAt = 5))
        assertEquals(setOf("open", "done"), dao.observeVisible().first().map { it.title }.toSet())
        assertEquals(listOf("gone"), dao.observeDeleted().first().map { it.title })
        assertEquals(1, dao.observeOpenCount().first())
    }

    @Test
    fun deleteForeverOnlyRemovesDeletedOnes() = runBlocking {
        val dao = db.todoDao()
        val live = dao.insert(TodoEntity(title = "live"))
        val gone = dao.insert(TodoEntity(title = "gone", deletedAt = 5))
        assertEquals("a visible to-do can't be removed for good by mistake", 0, dao.deleteForever(live))
        assertEquals(1, dao.deleteForever(gone))
        assertNull(dao.getById(gone))
    }

    @Test
    fun purgeRemovesOnlyOldDeletedOnes() = runBlocking {
        val dao = db.todoDao()
        dao.insert(TodoEntity(title = "old", deletedAt = 10))
        dao.insert(TodoEntity(title = "recent", deletedAt = 1000))
        dao.insert(TodoEntity(title = "live"))
        assertEquals(1, dao.purgeDeletedBefore(cutoff = 500))
        assertEquals(listOf("recent"), dao.observeDeleted().first().map { it.title })
        assertEquals(listOf("live"), dao.observeVisible().first().map { it.title })
    }

    @Test
    fun deletingAProjectKeepsItsToDos() = runBlocking {
        val dao = db.todoDao()
        val id = dao.insert(TodoEntity(title = "soil", projectId = 7))
        dao.clearProject(7)
        assertNull(dao.getById(id)!!.projectId)
    }
}
