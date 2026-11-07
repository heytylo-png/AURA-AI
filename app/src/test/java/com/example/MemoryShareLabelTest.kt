package com.example

import com.example.ui.screens.memoryShareLabel
import org.junit.Assert.assertEquals
import org.junit.Test

/** The share chip says plainly whether a memory is being sent, and if not, why. */
class MemoryShareLabelTest {
    @Test
    fun labels() {
        assertEquals("Share with Claude", memoryShareLabel(marked = false, active = true, sharingOn = true))
        assertEquals("Marked · memory is off", memoryShareLabel(marked = true, active = false, sharingOn = true))
        assertEquals("Marked · sharing is off in Settings", memoryShareLabel(marked = true, active = true, sharingOn = false))
        assertEquals("Shared with Claude", memoryShareLabel(marked = true, active = true, sharingOn = true))
    }
}
