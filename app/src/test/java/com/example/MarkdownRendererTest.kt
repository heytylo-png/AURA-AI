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

    private fun links(md: String): List<Pair<String, String>> {
        val out = MarkdownRenderer.render(md, Color.Gray, onLink = {})
        return out.getLinkAnnotations(0, out.length).map {
            out.text.substring(it.start, it.end) to (it.item as androidx.compose.ui.text.LinkAnnotation.Clickable).tag
        }
    }

    @Test
    fun webLinksCanBeTappedAndCarryTheirRealAddress() {
        assertEquals(listOf("the docs" to "https://example.com/a?b=1"), links("see [the docs](https://example.com/a?b=1) now"))
        // The text can claim one site while the address is another: the address is what is kept.
        assertEquals(listOf("https://bank.example" to "https://evil.example/login"), links("[https://bank.example](https://evil.example/login)"))
        assertEquals(listOf("titled" to "http://example.org"), links("[titled](http://example.org \"a title\")"))
        // Brackets inside an address belong to it.
        assertEquals(listOf("wiki" to "https://en.example/wiki/Mercury_(planet)"), links("[wiki](https://en.example/wiki/Mercury_(planet)) is hot"))
        assertEquals("wiki is hot", MarkdownRenderer.render("[wiki](https://en.example/wiki/Mercury_(planet)) is hot", Color.Gray).text)
    }

    @Test
    fun bareWebAddressesBecomeLinksWithoutTheSentencePunctuation() {
        assertEquals(listOf("https://example.com/page" to "https://example.com/page"), links("Read https://example.com/page."))
        assertEquals(listOf("https://a.example/x_y" to "https://a.example/x_y"), links("(see https://a.example/x_y)"))
        assertEquals("Read https://example.com/page.", MarkdownRenderer.render("Read https://example.com/page.", Color.Gray, onLink = {}).text)
    }

    @Test
    fun onlyWebAddressesBecomeLinks() {
        for (address in listOf("javascript:alert(1)", "intent://x#Intent;end", "file:///sdcard/a", "content://x/y", "tel:123", "mailto:a@b.c", "ftp://x.example")) {
            assertTrue(address, links("[tap me]($address)").isEmpty())
            assertEquals("tap me", MarkdownRenderer.render("[tap me]($address)", Color.Gray, onLink = {}).text)
        }
        // Without a handler (reading aloud, previews) nothing is a link.
        assertTrue(render("[the docs](https://example.com)").getLinkAnnotations(0, 8).isEmpty())
    }

    @Test
    fun theSiteShownBeforeOpeningIsTheRealOne() {
        assertEquals("evil.example", MarkdownRenderer.hostOf("https://evil.example/login?next=bank.example"))
        assertEquals("evil.example", MarkdownRenderer.hostOf("https://bank.example@evil.example/x"))
        assertEquals("example.com:8443", MarkdownRenderer.hostOf("https://example.com:8443"))
    }

    @Test
    fun plainTextForReadingAloud() {
        assertEquals("Hello world\n• item", MarkdownRenderer.plainText("**Hello** _world_\n- item"))
    }
}
