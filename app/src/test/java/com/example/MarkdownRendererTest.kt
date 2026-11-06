package com.example

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import com.example.ui.components.MarkdownRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** AI replies are shown formatted, never lose text, and never show stray Markdown markers. */
class MarkdownRendererTest {

    private fun render(md: String) = MarkdownRenderer.render(md, Color.Gray)

    private fun AnnotatedString.styledText(predicate: (androidx.compose.ui.text.SpanStyle) -> Boolean): List<String> =
        spanStyles.filter { predicate(it.item) }.map { text.substring(it.start, it.end) }

    @Test
    fun boldItalicAndCode() {
        val out = render("A **bold** and *italic* and `code` word")
        assertEquals("A bold and italic and code word", out.text)
        assertEquals(listOf("bold"), out.styledText { it.fontWeight == FontWeight.Bold })
        assertEquals(listOf("italic"), out.styledText { it.fontStyle == FontStyle.Italic })
        assertEquals(listOf("code"), out.styledText { it.fontFamily == FontFamily.Monospace })
    }

    @Test
    fun theReplyFromTheScreenshotHasNoStrayMarkers() {
        val out = render("**1. Thinking and analysis**\n- Break down problems\n- Summarize text")
        assertEquals("1. Thinking and analysis\n• Break down problems\n• Summarize text", out.text)
        assertEquals(listOf("1. Thinking and analysis"), out.styledText { it.fontWeight == FontWeight.Bold })
    }

    @Test
    fun headingsListsQuotesAndRules() {
        val out = render("## Plan\n1. First\n  * nested\n> careful\n---")
        assertEquals("Plan\n1. First\n   • nested\n▎ careful\n" + "─".repeat(12), out.text)
        assertTrue(out.styledText { it.fontWeight == FontWeight.Bold }.contains("Plan"))
    }

    @Test
    fun codeBlocksKeepTheirContentAsTyped() {
        val out = render("Run:\n```kotlin\nval x = a * b * c\n```\nDone")
        assertEquals("Run:\nval x = a * b * c\nDone", out.text)
        assertEquals(listOf("val x = a * b * c"), out.styledText { it.fontFamily == FontFamily.Monospace })
    }

    @Test
    fun unfinishedMarkersFromAStreamingReplyStayVisible() {
        assertEquals("Here is **the start", render("Here is **the start").text)
        assertEquals("a `b", render("a `b").text)
        assertEquals("2 * 3 = 6", render("2 * 3 = 6").text)
    }

    @Test
    fun wordsWithUnderscoresAreNotItalicised() {
        val out = render("use snake_case_names here")
        assertEquals("use snake_case_names here", out.text)
        assertTrue(out.spanStyles.isEmpty())
    }

    @Test
    fun linksShowTheirText() {
        assertEquals("see the docs now", render("see [the docs](https://example.com) now").text)
    }

    @Test
    fun plainTextForReadingAloud() {
        assertEquals("Hello world\n• item", MarkdownRenderer.plainText("**Hello** _world_\n- item"))
    }
}
