package com.example.data.model

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Rules for turning a picked file into a project note: text only, at most 200 KB. */
object NoteImport {
    const val MAX_BYTES = 200 * 1024
    /** Same limit as typed note content so the editor can still open it. */
    const val MAX_CHARS = 200_000

    /** UTF-8 text (BOM removed). Throws with a readable reason for binary files such as PDFs or images. */
    fun decode(bytes: ByteArray): String {
        require(bytes.size <= MAX_BYTES) { "That file is larger than 200 KB. Only text files up to 200 KB can be added." }
        if (bytes.any { it == 0.toByte() }) throw IllegalArgumentException(NOT_TEXT)
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            throw IllegalArgumentException(NOT_TEXT)
        }
        val clean = text.removePrefix("﻿").replace("\r\n", "\n")
        if (clean.isBlank()) throw IllegalArgumentException("That file is empty.")
        return clean.take(MAX_CHARS)
    }

    private const val NOT_TEXT = "That doesn't look like a text file. Text, Markdown, CSV and code files work; PDFs, Word files and images don't yet."
}
