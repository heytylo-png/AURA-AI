package com.example

import com.example.data.model.TodoEntity
import com.example.data.model.TodoRules
import com.example.reminders.ReminderPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/** When reminders fire: once, at the chosen hour on the due day, never for finished or deleted to-dos. */
class ReminderPlanTest {
    private val chicago = TimeZone.getTimeZone("America/Chicago")
    private val nov10 = TodoRules.epochDay(2026, 11, 10)

    private fun todo(id: Long, due: Long?, done: Boolean = false, deletedAt: Long? = null) =
        TodoEntity(id = id, title = "T$id", dueEpochDay = due, done = done, deletedAt = deletedAt, createdAt = id)

    @Test fun reminderIsAtLocalHourOnDueDay() {
        // 2026-11-10 09:00 CST = 15:00 UTC.
        assertEquals(TodoRules.epochDay(2026, 11, 10) * 86_400_000L + 15 * 3_600_000L, ReminderPlan.reminderAt(nov10, 9, chicago))
        // Summer (CDT, UTC-5): 2026-07-01 09:00 = 14:00 UTC.
        val jul1 = TodoRules.epochDay(2026, 7, 1)
        assertEquals(jul1 * 86_400_000L + 14 * 3_600_000L, ReminderPlan.reminderAt(jul1, 9, chicago))
    }

    @Test fun dueOnlyInsideWindowAndOnlyOpenToDos() {
        val at = ReminderPlan.reminderAt(nov10, 9, chicago)
        val todos = listOf(todo(1, nov10), todo(2, nov10, done = true), todo(3, nov10, deletedAt = 5), todo(4, null), todo(5, nov10 + 1))
        assertEquals(listOf(1L), ReminderPlan.due(todos, 9, after = at - 60_000, now = at + 60_000, zone = chicago).map { it.id })
        // Already handled: not shown twice.
        assertTrue(ReminderPlan.due(todos, 9, after = at, now = at + 3_600_000, zone = chicago).isEmpty())
        // Not yet time.
        assertTrue(ReminderPlan.due(todos, 9, after = at - 120_000, now = at - 60_000, zone = chicago).isEmpty())
    }

    @Test fun phoneOffForDaysDoesNotFloodOldReminders() {
        val now = ReminderPlan.reminderAt(nov10, 12, chicago)
        val todos = listOf(todo(1, nov10 - 3), todo(2, nov10 - 1), todo(3, nov10))
        val due = ReminderPlan.due(todos, 9, after = now - 7 * 86_400_000L, now = now, zone = chicago)
        assertEquals(listOf(3L), due.map { it.id })
    }

    @Test fun nextAlarmIsSoonestFutureReminder() {
        val now = ReminderPlan.reminderAt(nov10, 10, chicago)
        val todos = listOf(todo(1, nov10), todo(2, nov10 + 2), todo(3, nov10 + 1), todo(4, nov10 + 1, done = true))
        assertEquals(ReminderPlan.reminderAt(nov10 + 1, 9, chicago), ReminderPlan.nextAt(todos, 9, now, chicago))
        assertNull(ReminderPlan.nextAt(listOf(todo(1, nov10), todo(2, null)), 9, now, chicago))
    }

    @Test fun hourLabels() {
        assertEquals("9:00 AM", ReminderPlan.hourLabel(9))
        assertEquals("12:00 PM", ReminderPlan.hourLabel(12))
        assertEquals("6:00 PM", ReminderPlan.hourLabel(18))
    }
}
