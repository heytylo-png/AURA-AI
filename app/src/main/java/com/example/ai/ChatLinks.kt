package com.example.ai

/** Web links in a conversation, for deciding when Claude may open them and for showing which it opened. */
object ChatLinks {
    private val URL = Regex("""https?://[^\s<>"'`]+""", RegexOption.IGNORE_CASE)

    /** Whether [text] contains a web link. */
    fun hasLink(text: String): Boolean = URL.containsMatchIn(text)

    /** Whether the new message or an earlier turn sent with it contains a link Claude could open. */
    fun present(request: AiProviderRequest): Boolean =
        hasLink(request.prompt) || request.history.any { hasLink(it.text) }

    /** "example.com" for "https://www.example.com/a?b", or the link itself if it can't be read. */
    fun host(url: String): String =
        runCatching { java.net.URI(url.trim()).host?.removePrefix("www.") }.getOrNull()?.takeIf { it.isNotBlank() } ?: url.take(80)
}
