package com.example

import android.speech.SpeechRecognizer
import com.example.ui.components.breath
import com.example.voice.VoiceErrors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Voice errors read as plain language; the listening stars stay within their size range. */
class VoicePolishTest {
    @Test fun recognitionErrorsAreReadable() {
        assertTrue(VoiceErrors.message(SpeechRecognizer.ERROR_NO_MATCH)!!.startsWith("Didn't catch that"))
        assertTrue(VoiceErrors.message(SpeechRecognizer.ERROR_NETWORK)!!.contains("connection"))
        assertTrue(VoiceErrors.message(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)!!.contains("microphone"))
        assertEquals("Voice input stopped (error 99).", VoiceErrors.message(99))
        // Cancelled by the user: no message.
        assertNull(VoiceErrors.message(SpeechRecognizer.ERROR_CLIENT))
    }

    @Test fun breathRange() {
        assertEquals(0.8f, breath(0.35f), 1e-4f)
        assertEquals(1.2f, breath(1f), 1e-4f)
    }
}
