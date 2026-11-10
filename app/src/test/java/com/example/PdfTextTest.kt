package com.example

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.PdfText
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

/** PDFs with text become notes; empty (scanned-like) and broken PDFs are refused with a reason. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PdfTextTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun pdf(vararg lines: String): ByteArray {
        PDFBoxResourceLoader.init(app)
        PDDocument().use { doc ->
            val page = PDPage()
            doc.addPage(page)
            if (lines.isNotEmpty()) PDPageContentStream(doc, page).use { cs ->
                cs.beginText()
                cs.setFont(PDType1Font.HELVETICA, 12f)
                cs.newLineAtOffset(72f, 700f)
                lines.forEach { cs.showText(it); cs.newLineAtOffset(0f, -16f) }
                cs.endText()
            }
            return ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
        }
    }

    @Test fun extractsText() {
        val bytes = pdf("Video ideas for the channel", "Episode one: setting up the studio")
        assertTrue(PdfText.isPdf(bytes))
        val text = PdfText.extract(app, bytes)
        assertTrue(text, text.contains("Video ideas for the channel"))
        assertTrue(text.contains("Episode one"))
    }

    @Test fun refusesPdfWithoutTextAndBrokenFiles() {
        try { PdfText.extract(app, pdf()); fail() } catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("no readable text")) }
        val broken = "%PDF-1.7 this is not really a pdf".toByteArray()
        try { PdfText.extract(app, broken); fail() } catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("couldn't be read")) }
        assertFalse(PdfText.isPdf("hello".toByteArray()))
    }
}
