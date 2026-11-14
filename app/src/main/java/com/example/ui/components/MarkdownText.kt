package com.example.ui.components

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.withLink
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.em

/**
 * Shows AI replies with basic Markdown formatting: headings, bold, italics, strikethrough,
 * inline code, code blocks, bullet and numbered lists, quotes, rules and links.
 *
 * Markers without a closing partner are shown as typed, so half-streamed replies never lose text.
 *
 * Web links (http and https only) can be tapped. A link's text can say anything, so tapping never
 * opens it straight away: Aura first shows the real address and asks.
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    codeBackground: Color = Color(0x33888888)
) {
    var linkToOpen by remember { mutableStateOf<String?>(null) }
    val rendered = remember(text, codeBackground) {
        MarkdownRenderer.render(text, codeBackground, onLink = { linkToOpen = it })
    }
    Text(text = rendered, modifier = modifier, style = style, color = color)

    val link = linkToOpen
    if (link != null) {
        val uriHandler = LocalUriHandler.current
        AlertDialog(
            onDismissRequest = { linkToOpen = null },
            title = { Text("Open this link?") },
            text = {
                // The whole address, selectable, so it can be read or copied before opening.
                SelectionContainer {
                    Text(
                        text = "It opens in your browser, at ${MarkdownRenderer.hostOf(link)}:\n\n$link",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag("open_link_address")
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        linkToOpen = null
                        runCatching { uriHandler.openUri(link) }
                    },
                    modifier = Modifier.testTag("open_link_confirm")
                ) { Text("Open") }
            },
            dismissButton = { TextButton(onClick = { linkToOpen = null }) { Text("Cancel") } },
            modifier = Modifier.testTag("open_link_dialog")
        )
    }
}

object MarkdownRenderer {

    private val HEADING = Regex("""^(#{1,6})\s+(.*)$""")
    private val BULLET = Regex("""^(\s*)[-*+]\s+(.*)$""")
    private val NUMBERED = Regex("""^(\s*)(\d{1,3})[.)]\s+(.*)$""")
    private val QUOTE = Regex("""^\s*>\s?(.*)$""")
    private val RULE = Regex("""^\s*([-*_])(\s*\1){2,}\s*$""")

    /** Text with the Markdown markers removed, e.g. for reading aloud. */
    fun plainText(markdown: String): String = render(markdown, Color.Unspecified).text

    private val WEB_ADDRESS = Regex("""https?://[^\s<>"]+""")

    /** The site a web address points at, for showing before it is opened. */
    fun hostOf(url: String): String =
        url.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@')

    /**
     * [onLink], when given, makes web links tappable and is called with the address of the one
     * tapped. Only http and https addresses become links; anything else stays plain text.
     */
    fun render(markdown: String, codeBackground: Color, onLink: ((String) -> Unit)? = null): AnnotatedString = buildAnnotatedString {
        this@MarkdownRenderer.onLink.set(onLink)
        try {
            renderLines(markdown, codeBackground)
        } finally {
            this@MarkdownRenderer.onLink.remove()
        }
    }.trimTrailingNewlines()

    private val insideLinkText = ThreadLocal<Boolean>()

    /** The ")" that ends a link's address starting at [from], allowing "(...)" inside the address. */
    private fun closingParen(text: String, from: Int): Int {
        var depth = 0
        for (j in from until text.length) {
            when (text[j]) {
                '(' -> depth++
                ')' -> if (depth == 0) return j else depth--
            }
        }
        return -1
    }

    // The handler for the render in progress on this thread (rendering is recursive but not re-entrant).
    private val onLink = ThreadLocal<((String) -> Unit)?>()

    private fun AnnotatedString.Builder.appendLink(url: String, content: AnnotatedString.Builder.() -> Unit) {
        val handler = onLink.get()
        val underline = SpanStyle(textDecoration = TextDecoration.Underline)
        if (handler == null || !WEB_ADDRESS.matches(url)) {
            withStyle(underline) { content() }
        } else {
            withLink(LinkAnnotation.Clickable(tag = url, styles = TextLinkStyles(style = underline)) { handler(url) }) { content() }
        }
    }

    private fun AnnotatedString.Builder.renderLines(markdown: String, codeBackground: Color) {
        val code = SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)
        var inFence = false
        val lines = markdown.replace("\r\n", "\n").split("\n")
        lines.forEachIndexed { index, line ->
            if (line.trimStart().startsWith("```")) {
                inFence = !inFence
                // The fence line itself is not shown; drop its line break too.
                return@forEachIndexed
            }
            if (inFence) {
                withStyle(code) { append(line) }
            } else {
                appendBlockLine(line, code)
            }
            if (index < lines.lastIndex) append('\n')
        }
    }

    private fun AnnotatedString.trimTrailingNewlines(): AnnotatedString {
        var end = text.length
        while (end > 0 && text[end - 1] == '\n') end--
        return if (end == text.length) this else subSequence(0, end)
    }

    private fun AnnotatedString.Builder.appendBlockLine(line: String, code: SpanStyle) {
        HEADING.matchEntire(line)?.let { m ->
            val size = when (m.groupValues[1].length) { 1 -> 1.3.em; 2 -> 1.2.em; 3 -> 1.1.em; else -> 1.0.em }
            withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = size)) { appendInline(m.groupValues[2], code) }
            return
        }
        if (RULE.matches(line)) {
            append("─".repeat(12))
            return
        }
        BULLET.matchEntire(line)?.let { m ->
            append(indent(m.groupValues[1]))
            append("• ")
            appendInline(m.groupValues[2], code)
            return
        }
        NUMBERED.matchEntire(line)?.let { m ->
            append(indent(m.groupValues[1]))
            append("${m.groupValues[2]}. ")
            appendInline(m.groupValues[3], code)
            return
        }
        QUOTE.matchEntire(line)?.let { m ->
            append("▎ ")
            withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { appendInline(m.groupValues[1], code) }
            return
        }
        appendInline(line, code)
    }

    private fun indent(spaces: String): String = "   ".repeat(spaces.replace("\t", "  ").length / 2)

    private fun AnnotatedString.Builder.appendInline(text: String, code: SpanStyle) {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\\' && i + 1 < text.length && text[i + 1] in ESCAPABLE -> {
                    append(text[i + 1])
                    i += 2
                }
                c == '`' -> {
                    val end = text.indexOf('`', i + 1)
                    if (end > i + 1) {
                        withStyle(code) { append(text.substring(i + 1, end)) }
                        i = end + 1
                    } else {
                        append(c); i++
                    }
                }
                text.startsWith("**", i) || text.startsWith("__", i) -> {
                    i = wrapped(text, i, text.substring(i, i + 2), SpanStyle(fontWeight = FontWeight.Bold), code)
                }
                text.startsWith("~~", i) -> {
                    i = wrapped(text, i, "~~", SpanStyle(textDecoration = TextDecoration.LineThrough), code)
                }
                c == '*' || (c == '_' && (i == 0 || !text[i - 1].isLetterOrDigit())) -> {
                    i = wrapped(text, i, c.toString(), SpanStyle(fontStyle = FontStyle.Italic), code)
                }
                (c == 'h') && (text.startsWith("https://", i) || text.startsWith("http://", i)) &&
                    (i == 0 || !text[i - 1].isLetterOrDigit()) && insideLinkText.get() != true -> {
                    // A bare web address. Punctuation that ends the sentence is not part of it.
                    val url = WEB_ADDRESS.find(text, i)!!.value.trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '\'', '*', '_')
                    appendLink(url) { append(url) }
                    i += url.length
                }
                c == '[' -> {
                    val close = text.indexOf("](", i + 1)
                    val end = if (close > i + 1) closingParen(text, close + 2) else -1
                    if (end > close) {
                        // "[text](address)" or "[text](address "title")": the address is the first word.
                        val url = text.substring(close + 2, end).trim().substringBefore(' ')
                        // Inside a link's text, a web address is just text: the link goes where its
                        // own address says, never somewhere else for part of it.
                        insideLinkText.set(true)
                        try {
                            appendLink(url) { appendInline(text.substring(i + 1, close), code) }
                        } finally {
                            insideLinkText.set(false)
                        }
                        i = end + 1
                    } else {
                        append(c); i++
                    }
                }
                else -> {
                    append(c); i++
                }
            }
        }
    }

    /**
     * Styles text between [marker] at [start] and its closing [marker]; returns the next index.
     * Without a valid closing marker the opening marker is kept as plain text.
     */
    private fun AnnotatedString.Builder.wrapped(
        text: String,
        start: Int,
        marker: String,
        style: SpanStyle,
        code: SpanStyle
    ): Int {
        val contentStart = start + marker.length
        val end = findClosing(text, contentStart, marker)
        if (end < 0) {
            append(marker)
            return contentStart
        }
        withStyle(style) { appendInline(text.substring(contentStart, end), code) }
        return end + marker.length
    }

    private fun findClosing(text: String, from: Int, marker: String): Int {
        if (from >= text.length || text[from].isWhitespace()) return -1
        var j = text.indexOf(marker, from)
        while (j >= 0) {
            val beforeIsSpace = text[j - 1].isWhitespace()
            // A single * or _ must not be the first half of ** or __.
            val doubled = marker.length == 1 && j + 1 < text.length && text[j + 1] == marker[0]
            // A closing _ must not sit inside a word (snake_case).
            val inWord = marker == "_" && j + 1 < text.length && text[j + 1].isLetterOrDigit()
            if (j > from && !beforeIsSpace && !doubled && !inWord) return j
            j = text.indexOf(marker, j + if (doubled) 2 else 1)
        }
        return -1
    }

    private const val ESCAPABLE = "\\`*_~[]()#>-+.!"
}
