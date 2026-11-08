package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A to-do. Lives only on the phone. "Deleted" to-dos keep [deletedAt] and sit in Recently
 * deleted for [TodoRules.KEEP_DELETED_DAYS] days, so they can be restored exactly as they were.
 */
@Entity(tableName = "todos")
data class TodoEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val notes: String = "",
    /** Due date as days since 1970-01-01 (no time of day, no alarm). */
    val dueEpochDay: Long? = null,
    val projectId: Long? = null,
    val done: Boolean = false,
    val doneAt: Long? = null,
    val source: TodoSource? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long? = null,
    val deletedAt: Long? = null
)

enum class TodoSource(val label: String) {
    TYPED("Added by you"),
    CHAT_APPROVED("From chat, approved by you"),
    ON_PHONE_RULE("Suggested on this phone, approved by you")
}

/** The rules for to-dos, kept in one place so the UI, executor and tests agree. */
object TodoRules {
    const val MAX_TITLE = 200
    const val MAX_NOTES = 2000
    const val KEEP_DELETED_DAYS = 30L

    /** Cleaned title and notes, or null if they break a limit. */
    fun clean(title: String, notes: String): Pair<String, String>? {
        val t = title.trim()
        val n = notes.trim()
        if (t.isEmpty() || t.length > MAX_TITLE || n.length > MAX_NOTES) return null
        return t to n
    }

    /** Parses "YYYY-MM-DD"; null for blank, throws for an invalid date. (No java.time: minSdk is 24.) */
    fun parseDue(text: String?): Long? {
        val s = text?.trim().orEmpty()
        if (s.isEmpty()) return null
        val m = Regex("""(\d{4})-(\d{2})-(\d{2})""").matchEntire(s) ?: throw IllegalArgumentException("Not a date: $s")
        val (y, mo, d) = m.destructured
        val year = y.toInt(); val month = mo.toInt(); val day = d.toInt()
        require(month in 1..12 && day in 1..daysInMonth(year, month)) { "Not a date: $s" }
        return epochDay(year, month, day)
    }

    /** "YYYY-MM-DD" for a day number. */
    fun formatDue(epochDay: Long): String {
        val (y, m, d) = civil(epochDay)
        return "%04d-%02d-%02d".format(y, m, d)
    }

    /** Today's day number in the phone's time zone. */
    fun today(now: Long = System.currentTimeMillis(), zone: java.util.TimeZone = java.util.TimeZone.getDefault()): Long =
        Math.floorDiv(now + zone.getOffset(now), 86_400_000L)

    private fun isLeap(y: Int) = (y % 4 == 0 && y % 100 != 0) || y % 400 == 0
    private fun daysInMonth(y: Int, m: Int) = when (m) { 2 -> if (isLeap(y)) 29 else 28; 4, 6, 9, 11 -> 30; else -> 31 }

    // Howard Hinnant's days_from_civil / civil_from_days.
    fun epochDay(y0: Int, m: Int, d: Int): Long {
        val y = if (m <= 2) y0 - 1 else y0
        val era = Math.floorDiv(y, 400)
        val yoe = y - era * 400
        val doy = (153 * (if (m > 2) m - 3 else m + 9) + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097L + doe - 719468
    }

    fun civil(z0: Long): Triple<Int, Int, Int> {
        val z = z0 + 719468
        val era = Math.floorDiv(z, 146097L)
        val doe = (z - era * 146097).toInt()
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = if (mp < 10) mp + 3 else mp - 9
        val y = yoe + era.toInt() * 400 + if (m <= 2) 1 else 0
        return Triple(y, m, d)
    }

    /** Open to-dos first: overdue and soonest due first, undated last, then oldest first. */
    val openOrder: Comparator<TodoEntity> =
        compareBy<TodoEntity> { it.dueEpochDay == null }.thenBy { it.dueEpochDay ?: 0 }.thenBy { it.createdAt }

    fun isOverdue(todo: TodoEntity, today: Long): Boolean =
        !todo.done && todo.dueEpochDay != null && todo.dueEpochDay < today

    fun purgeCutoff(now: Long): Long = now - KEEP_DELETED_DAYS * 24 * 60 * 60 * 1000
}

fun TodoEntity.edited(title: String, notes: String, dueEpochDay: Long?, projectId: Long?, now: Long): TodoEntity? {
    val (t, n) = TodoRules.clean(title, notes) ?: return null
    return copy(title = t, notes = n, dueEpochDay = dueEpochDay, projectId = projectId, updatedAt = now)
}

fun TodoEntity.toggledDone(now: Long): TodoEntity =
    if (done) copy(done = false, doneAt = null) else copy(done = true, doneAt = now)

fun TodoEntity.movedToDeleted(now: Long): TodoEntity = copy(deletedAt = now)

fun TodoEntity.restored(): TodoEntity = copy(deletedAt = null)
