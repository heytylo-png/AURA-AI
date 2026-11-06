package com.example.ui.components

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
 * inline code, code blocks, bullet and numbered lists, quotes, rules and link text.
 *
 * Markers without a closing partner are shown as typed, so half-streamed replies never lose text.
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    codeBackground: Color = Color(0x33888888)
) {
    val rendered = remember(text, codeBackground) { MarkdownRenderer.render(text, codeBackground) }
    Text(text = rendered, modifier = modifier, style = style, color = color)
}

object MarkdownRenderer {

    private val HEADING = Regex("""^(#{1,6})\s+(.*)$""")
    private val BULLET = Regex("""^(\s*)[-*+]\s+(.*)$""")
    private val NUMBERED = Regex("""^(\s*)(\d{1,3})[.)]\s+(.*)$""")
    private val QUOTE = Regex("""^\s*>\s?(.*)$""")
    private val RULE = Regex("""^\s*([-*_])(\s*\1){2,}\s*$""")

    /** Text with the Markdown markers removed, e.g. for reading aloud. */
    fun plainText(markdown: String): String = render(markdown, Color.Unspecified).text

    fun render(markdown: String, codeBackground: Color): AnnotatedString = buildAnnotatedString {
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
    }.trimTrailingNewlines()

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
                c == '[' -> {
                    val close = text.indexOf("](", i + 1)
                    val end = if (close > i + 1) text.indexOf(')', close + 2) else -1
                    if (end > close) {
                        withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) {
                            appendInline(text.substring(i + 1, close), code)
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
