package com.example

import com.example.data.model.TodoEntity
import com.example.data.model.TodoRules
import com.example.data.model.edited
import com.example.data.model.movedToDeleted
import com.example.data.model.restored
import com.example.data.model.toggledDone
import com.example.ui.components.dueLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/** Edit, finish, reopen, delete and restore behave exactly as the Tasks tab says. */
class TodoRulesTest {

    private val base = TodoEntity(id = 3, title = "Buy soil", notes = "", createdAt = 100)

    @Test
    fun datesRoundTripWithoutJavaTime() {
        assertEquals(0L, TodoRules.parseDue("1970-01-01"))
        assertEquals("2026-11-08", TodoRules.formatDue(TodoRules.parseDue("2026-11-08")!!))
        assertEquals("2024-02-29", TodoRules.formatDue(TodoRules.parseDue("2024-02-29")!!))
        assertEquals("1969-12-31", TodoRules.formatDue(-1))
        assertNull(TodoRules.parseDue("  "))
        for (bad in listOf("2026-02-30", "2025-02-29", "2026-13-01", "26-11-08", "tomorrow")) {
            assertTrue(bad, runCatching { TodoRules.parseDue(bad) }.isFailure)
        }
    }

    @Test
    fun todayUsesThePhonesTimeZone() {
        val justAfterMidnightUtc = 30L * 60 * 1000 // 1970-01-01 00:30 UTC
        assertEquals(0L, TodoRules.today(justAfterMidnightUtc, TimeZone.getTimeZone("UTC")))
        assertEquals(-1L, TodoRules.today(justAfterMidnightUtc, TimeZone.getTimeZone("America/Chicago")))
    }

    @Test
    fun openListOrder() {
        val a = base.copy(id = 1, dueEpochDay = 20)
        val b = base.copy(id = 2, dueEpochDay = 10)
        val c = base.copy(id = 3, dueEpochDay = null, createdAt = 1)
        val d = base.copy(id = 4, dueEpochDay = null, createdAt = 2)
        assertEquals(listOf(2L, 1L, 3L, 4L), listOf(d, a, c, b).sortedWith(TodoRules.openOrder).map { it.id })
    }

    @Test
    fun overdueAndLabels() {
        assertTrue(TodoRules.isOverdue(base.copy(dueEpochDay = 9), today = 10))
        assertFalse(TodoRules.isOverdue(base.copy(dueEpochDay = 9, done = true), today = 10))
        assertFalse(TodoRules.isOverdue(base.copy(dueEpochDay = 10), today = 10))
        assertEquals("Due today", dueLabel(10, 10))
        assertEquals("Due tomorrow", dueLabel(11, 10))
        assertEquals("Overdue · 1970-01-10", dueLabel(9, 10))
    }

    @Test
    fun editKeepsIdentityAndChecksLimits() {
        val e = base.copy(done = true, doneAt = 50).edited(" Buy compost ", " 2 bags ", 30, 7, now = 999)!!
        assertEquals(3L, e.id)
        assertEquals("Buy compost", e.title)
        assertEquals("2 bags", e.notes)
        assertEquals(30L, e.dueEpochDay)
        assertEquals(7L, e.projectId)
        assertTrue("editing a done to-do keeps it done", e.done)
        assertEquals(999L, e.updatedAt)
        assertNull(base.edited("  ", "", null, null, 1))
        assertNull(base.edited("x".repeat(201), "", null, null, 1))
        assertNull(base.edited("ok", "x".repeat(2001), null, null, 1))
    }

    @Test
    fun finishReopenDeleteRestore() {
        val done = base.toggledDone(now = 500)
        assertTrue(done.done); assertEquals(500L, done.doneAt)
        val reopened = done.toggledDone(now = 600)
        assertFalse(reopened.done); assertNull(reopened.doneAt)

        val deleted = done.movedToDeleted(now = 700)
        assertEquals(700L, deleted.deletedAt)
        val restored = deleted.restored()
        assertEquals("restore brings it back exactly as it was", done, restored)
    }

    @Test
    fun deletedToDosAreKeptThirtyDays() {
        val now = 100L * 86_400_000
        assertEquals(70L * 86_400_000, TodoRules.purgeCutoff(now))
    }
}
