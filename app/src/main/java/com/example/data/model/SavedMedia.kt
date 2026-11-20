package com.example.data.model

import android.content.Context
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.io.File
import java.util.UUID

/**
 * A picture, sticker or video the user saved to Aura's library. The library keeps its own copy of the
 * file (in [MediaLibrary.dir]), so it stays when the chat it came from is cleared or deleted.
 */
@Entity(tableName = "saved_media")
data class SavedMediaEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** The library file's name (see [MediaLibrary.isValidName]). */
    val fileName: String,
    /** [MediaLibrary.KIND_IMAGE], [MediaLibrary.KIND_STICKER] or [MediaLibrary.KIND_VIDEO]. */
    val kind: String,
    /** What the user calls it, e.g. "my dog". Needed for a reference. */
    val label: String = "",
    /**
     * A reference Claude may be shown: when the user's message mentions [label], the image goes to
     * Claude with that message (only images and stickers can be references).
     */
    val isReference: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
) {
    val isVideo get() = kind == MediaLibrary.KIND_VIDEO
}

/** The files behind Aura's library. Everything here stays on the phone. */
object MediaLibrary {
    const val KIND_IMAGE = "IMAGE"
    const val KIND_STICKER = "STICKER"
    const val KIND_VIDEO = "VIDEO"
    const val MAX_LABEL = 60
    /** Most reference images sent with one message. */
    const val MAX_REFERENCES_PER_MESSAGE = 3
    private const val DIR = "library"
    private val NAME = Regex("lib_[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(jpg|png|mp4|webm|3gp|mkv|mov)")
    private val VIDEO_EXTENSIONS = setOf("mp4", "webm", "3gp", "mkv", "mov")

    fun isValidName(name: String) = NAME.matches(name)

    fun dir(context: Context): File = File(context.filesDir, DIR).apply { mkdirs() }

    fun file(context: Context, name: String): File? =
        if (isValidName(name)) File(dir(context), name).takeIf { it.isFile } else null

    fun extensionOf(name: String) = name.substringAfterLast('.', "").lowercase()

    fun isVideoName(name: String) = extensionOf(name) in VIDEO_EXTENSIONS

    /** The MIME type to give other apps for a stored file (library, chat image or chat video). */
    fun mimeType(name: String): String = when (extensionOf(name)) {
        "jpg" -> "image/jpeg"
        "png" -> "image/png"
        "webm" -> "video/webm"
        "3gp" -> "video/3gpp"
        "mkv" -> "video/x-matroska"
        "mov" -> "video/quicktime"
        else -> "video/mp4"
    }

    /** Any stored picture or video by name: a chat image, a chat video or a library file. */
    fun find(context: Context, name: String): File? =
        ChatImages.file(context, name) ?: ChatVideos.file(context, name) ?: file(context, name)

    /** Whether a stored file (by name) is a video. */
    fun isVideoFile(name: String) = ChatVideos.isValidName(name) || (isValidName(name) && isVideoName(name))

    /** Copies a file into the library under a new name; returns the name. */
    fun copyIn(context: Context, source: File): String {
        val extension = extensionOf(source.name).takeIf { it in VIDEO_EXTENSIONS || it == "jpg" || it == "png" }
            ?: throw IllegalArgumentException("Aura can't keep that kind of file.")
        val name = "lib_${UUID.randomUUID()}.$extension"
        val partial = File(dir(context), "$name.part")
        try {
            source.copyTo(partial, overwrite = true)
            if (!partial.renameTo(File(dir(context), name))) throw IllegalStateException("Couldn't save it to the library.")
        } finally {
            partial.delete()
        }
        return name
    }

    /** Stores bytes (a made sticker or image) directly in the library; returns the name. */
    fun writeIn(context: Context, bytes: ByteArray, extension: String): String {
        require(extension == "jpg" || extension == "png")
        val name = "lib_${UUID.randomUUID()}.$extension"
        val partial = File(dir(context), "$name.part")
        partial.writeBytes(bytes)
        if (!partial.renameTo(File(dir(context), name))) {
            partial.delete()
            throw IllegalStateException("Couldn't save it to the library.")
        }
        return name
    }

    /** Deletes library files nothing refers to any more (items removed). Returns how many. */
    fun deleteUnused(context: Context, inUse: Set<String>): Int {
        var removed = 0
        dir(context).listFiles()?.forEach { f -> if (f.name !in inUse && f.delete()) removed++ }
        return removed
    }

    /** The kind to store for a file name. */
    fun kindFor(name: String, sticker: Boolean = false) = when {
        isVideoName(name) -> KIND_VIDEO
        sticker -> KIND_STICKER
        else -> KIND_IMAGE
    }

    /** "my dog" -> matches "Is my dog fat?", not "my doghouse". Case and spacing don't matter. */
    fun mentions(text: String, label: String): Boolean {
        val words = label.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return false
        val pattern = words.joinToString("\\s+") { Regex.escape(it) }
        return Regex("(?<![\\p{L}\\p{N}])$pattern(?![\\p{L}\\p{N}])").containsMatchIn(text.lowercase())
    }

    /** The references a message mentions, at most [MAX_REFERENCES_PER_MESSAGE], longest label first. */
    fun referencesIn(text: String, items: List<SavedMediaEntity>): List<SavedMediaEntity> =
        items.filter { it.isReference && !it.isVideo && it.label.isNotBlank() && mentions(text, it.label) }
            .sortedByDescending { it.label.length }
            .take(MAX_REFERENCES_PER_MESSAGE)
}
