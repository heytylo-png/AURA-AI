package com.example.data.model

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper

/**
 * Pulls the text out of a PDF on the phone, for a project note. Scanned PDFs (pictures of pages)
 * have no text layer, so they are refused with a clear reason rather than imported empty.
 */
object PdfText {
    const val MAX_BYTES = 10 * 1024 * 1024
    const val MAX_PAGES = 300

    fun isPdf(bytes: ByteArray): Boolean =
        bytes.size >= 5 && String(bytes, 0, 5, Charsets.US_ASCII) == "%PDF-"

    fun extract(context: Context, bytes: ByteArray): String {
        require(bytes.size <= MAX_BYTES) { "That PDF is larger than 10 MB." }
        PDFBoxResourceLoader.init(context.applicationContext)
        val doc = try {
            PDDocument.load(bytes)
        } catch (e: InvalidPasswordException) {
            throw IllegalArgumentException("That PDF is password-protected. Remove the password and try again.")
        } catch (e: Exception) {
            throw IllegalArgumentException("That PDF couldn't be read (it may be damaged).")
        }
        return doc.use {
            val pages = it.numberOfPages
            val stripper = PDFTextStripper().apply {
                startPage = 1
                endPage = minOf(pages, MAX_PAGES)
            }
            val text = stripper.getText(it)
                .replace("\r\n", "\n")
                .replace(Regex("[ \\t]+\n"), "\n")
                .replace(Regex("\n{3,}"), "\n\n")
                .trim()
            if (text.length < 20) {
                throw IllegalArgumentException("That PDF has no readable text (it may be scanned images). Only PDFs with selectable text can be added.")
            }
            val note = if (pages > MAX_PAGES) "$text\n\n[Only the first $MAX_PAGES of $pages pages were imported.]" else text
            note.take(NoteImport.MAX_CHARS)
        }
    }
}
