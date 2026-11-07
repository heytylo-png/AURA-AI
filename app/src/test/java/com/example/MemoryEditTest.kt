package com.example

import com.example.data.model.MemoryCategory
import com.example.data.model.MemoryEntity
import com.example.data.model.MemorySource
import com.example.data.model.edited
import com.example.data.model.sourceLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Editing a memory changes only what the user changed, and every memory says where it came from. */
class MemoryEditTest {

    private val original = MemoryEntity(
        id = 7,
        content = "Likes tea",
        category = MemoryCategory.PREFERENCE,
        importance = 3,
        isActive = false,
        createdAt = 100,
        lastAccessedAt = 200,
        source = MemorySource.CHAT_APPROVED
    )

    @Test
    fun editKeepsIdentityOriginAndStateAndRecordsTheTime() {
        val result = original.edited("  Likes green tea  ", MemoryCategory.FACT, 5, now = 999)!!
        assertEquals(7L, result.id)
        assertEquals("Likes green tea", result.content)
        assertEquals(MemoryCategory.FACT, result.category)
        assertEquals(5, result.importance)
        assertEquals(false, result.isActive)
        assertEquals(100L, result.createdAt)
        assertEquals(MemorySource.CHAT_APPROVED, result.source)
        assertEquals(999L, result.updatedAt)
    }

    @Test
    fun blankEditIsIgnored() {
        assertNull(original.edited("   ", MemoryCategory.FACT, 3, now = 1))
    }

    @Test
    fun importanceStaysInRange() {
        assertEquals(5, original.edited("x", MemoryCategory.FACT, 9, now = 1)!!.importance)
        assertEquals(1, original.edited("x", MemoryCategory.FACT, 0, now = 1)!!.importance)
    }

    @Test
    fun everyMemoryHasAnOriginLabel() {
        assertEquals("Added by you", original.copy(source = MemorySource.TYPED).sourceLabel)
        assertEquals("From chat, approved by you", original.sourceLabel)
        assertEquals("Saved by an older version of Aura", original.copy(source = null).sourceLabel)
    }
}
