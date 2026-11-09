package com.example.reminders

import com.example.data.model.TodoEntity
import java.util.TimeZone

/**
 * When to-do reminders fire. Pure logic (no Android), so it is unit-tested directly.
 *
 * One reminder per open to-do with a due date: at [hour]:00 local time on the due day.
 * A reminder fires if its time falls after the last time reminders were handled and not after now,
 * and only for to-dos due today or later, so a phone that was off for days doesn't flood old ones.
 */
object ReminderPlan {
    const val DEFAULT_HOUR = 9
    val HOURS = 5..22

    /** Epoch millis of [hour]:00 local time on [epochDay] in [zone]. */
    fun reminderAt(epochDay: Long, hour: Int, zone: TimeZone = TimeZone.getDefault()): Long {
        val local = epochDay * DAY + hour * HOUR_MS
        val guess = local - zone.getOffset(local)
        return local - zone.getOffset(guess)
    }

    private fun candidates(todos: List<TodoEntity>) =
        todos.filter { !it.done && it.deletedAt == null && it.dueEpochDay != null }

    /** To-dos whose reminder time is in (after, now] and that are due today or later. */
    fun due(
        todos: List<TodoEntity>, hour: Int, after: Long, now: Long,
        zone: TimeZone = TimeZone.getDefault()
    ): List<TodoEntity> {
        val today = Math.floorDiv(now + zone.getOffset(now), DAY)
        return candidates(todos).filter { t ->
            val at = reminderAt(t.dueEpochDay!!, hour, zone)
            t.dueEpochDay >= today && at > after && at <= now
        }.sortedBy { it.createdAt }
    }

    /** The next reminder time after [now], or null if none is coming. */
    fun nextAt(todos: List<TodoEntity>, hour: Int, now: Long, zone: TimeZone = TimeZone.getDefault()): Long? =
        candidates(todos).map { reminderAt(it.dueEpochDay!!, hour, zone) }.filter { it > now }.minOrNull()

    /** "9:00 AM" style label for an hour. */
    fun hourLabel(hour: Int): String {
        val h12 = if (hour % 12 == 0) 12 else hour % 12
        return "$h12:00 ${if (hour < 12) "AM" else "PM"}"
    }

    private const val HOUR_MS = 3_600_000L
    private const val DAY = 86_400_000L
}
