package com.example

import com.example.data.model.AiModelUsed
import com.example.ui.components.MessageNotice
import com.example.ui.components.MessageNotice.Kind
import com.example.ui.components.twinkle
import com.example.ui.theme.Grain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Warnings and failures get their own box; the grain stays faint; the stars take turns. */
class ThemePiecesTest {

    @Test
    fun normalReplyHasNoNotice() {
        val p = MessageNotice.present("Here are five ideas.", AiModelUsed.CLOUD)
        assertEquals("Here are five ideas.", p.body)
        assertNull(p.notice)
    }

    @Test
    fun incompleteAnswerKeepsTheTextAndAddsAWarningUnderIt() {
        val p = MessageNotice.present(
            "First half\n\n⚠ This answer is incomplete: Claude stopped responding for 60 seconds; the reply is incomplete.",
            AiModelUsed.CLOUD
        )
        assertEquals("First half", p.body)
        assertEquals(Kind.WARN, p.notice?.kind)
        assertEquals("This answer is incomplete", p.notice?.title)
        assertEquals("Claude stopped responding for 60 seconds; the reply is incomplete.", p.notice?.detail)
        assertTrue(p.noticeAfterBody)
    }

    @Test
    fun incompleteReasonIsWrittenAsASentence() {
        val p = MessageNotice.present("x\n\n⚠ This answer is incomplete: the stream ended early", AiModelUsed.CLOUD)
        assertEquals("The stream ended early.", p.notice?.detail)
    }

    @Test
    fun offlineFallbackShowsAWarningAboveTheOfflineReply() {
        val p = MessageNotice.present(
            "⚠ Claude is unavailable (Couldn't reach the Claude relay). Offline reply:\n\nHello from the phone",
            AiModelUsed.LOCAL
        )
        assertEquals("Hello from the phone", p.body)
        assertEquals(Kind.WARN, p.notice?.kind)
        assertEquals("Claude is unavailable", p.notice?.title)
        assertEquals("Couldn't reach the Claude relay. Below is Aura's offline reply.", p.notice?.detail)
        assertFalse(p.noticeAfterBody)
    }

    @Test
    fun cloudFailureIsAnError() {
        val p = MessageNotice.present(
            "Claude couldn't answer this message.\n\nClaude relay error (HTTP 403): not allowed.",
            AiModelUsed.NONE
        )
        assertEquals("", p.body)
        assertEquals(Kind.ERROR, p.notice?.kind)
        assertEquals("Claude relay error (HTTP 403): not allowed.", p.notice?.detail)
    }

    @Test
    fun engineErrorIsAnError() {
        val p = MessageNotice.present("Error: boom", AiModelUsed.NONE)
        assertEquals(Kind.ERROR, p.notice?.kind)
        assertEquals("boom", p.notice?.detail)
    }

    @Test
    fun cloudOffNoticeIsNeutral() {
        val text = "Nothing was sent: cloud chat is off. Turn on \"Cloud chat\" in Settings → Cloud AI, or switch to Auto or Local mode."
        val p = MessageNotice.present(text, AiModelUsed.NONE)
        assertEquals(Kind.INFO, p.notice?.kind)
        assertEquals(text, p.notice?.detail)
    }

    @Test
    fun grainIsFaintAndRepeatable() {
        val pixels = Grain.pixels()
        assertEquals(Grain.TILE * Grain.TILE, pixels.size)
        val maxAlpha = pixels.maxOf { it ushr 24 }
        assertTrue("grain must stay faint", maxAlpha <= 48)
        val specks = pixels.count { it != 0 }.toDouble() / pixels.size
        assertTrue("about a quarter of pixels are specks", specks in 0.2..0.36)
        assertTrue(Grain.pixels().contentEquals(pixels))
    }

    @Test
    fun starsBrightenInTurn() {
        // At each third of the cycle a different star is at its brightest.
        assertEquals(1f, twinkle(0.5f, 0), 1e-4f)
        assertEquals(1f, twinkle(0.5f + 1f / 3f, 1), 1e-4f)
        assertEquals(1f, twinkle((0.5f + 2f / 3f) % 1f, 2), 1e-4f)
        for (i in 0..2) for (step in 0..20) {
            val v = twinkle(step / 20f, i)
            assertTrue(v in 0.35f..1f)
        }
    }
}
