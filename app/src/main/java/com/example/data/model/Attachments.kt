package com.example.data.model

/**
 * What a chat message has attached. Messages keep their attachments' file names in one column
 * (`imageName`), separated by commas; the names never contain commas (see [ChatImages.isValidName]).
 */
object Attachments {
    /** Most pictures sent with one message. */
    const val MAX_PICTURES = 6

    /** The attachment names stored with a message, oldest first; unknown or damaged entries are skipped. */
    fun names(stored: String?): List<String> =
        stored?.split(',')?.map { it.trim() }?.filter { ChatImages.isValidName(it) } ?: emptyList()

    /** The value to store for [names], or null when there are none. */
    fun join(names: List<String>): String? = names.takeIf { it.isNotEmpty() }?.joinToString(",")

    /** "an image", "3 images" */
    fun describe(count: Int): String = if (count == 1) "an image" else "$count images"
}

/** The names of the files attached to this message. */
val ChatMessageEntity.attachmentNames: List<String> get() = Attachments.names(imageName)
