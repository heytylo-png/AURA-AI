package com.example.data.model

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Videos attached to chat messages. Everything here runs on the phone.
 *
 * A picked video is copied into Aura's private storage, so it still plays after it's deleted from the
 * gallery. Claude can't take video, so it gets a few still frames taken evenly through the video,
 * shrunk and re-encoded as JPEG (no sound, no location or other metadata). The video file itself
 * never leaves the phone. Videos are not part of backups, because of their size.
 */
object ChatVideos {
    /** Largest video accepted from the picker. */
    const val MAX_INPUT_BYTES = 300L * 1024 * 1024
    /** Most still frames sent to Claude for one video. */
    const val MAX_FRAMES = 8
    /** About one frame per this many milliseconds, for short videos. */
    const val MS_PER_FRAME = 2_000L
    /** Frames are smaller than photos: enough to see what happens, at a lower cost per frame. */
    const val FRAME_LONG_EDGE = 768
    private const val DIR = "chat_videos"
    private const val PART = ".part"
    private val EXTENSIONS = mapOf(
        "video/mp4" to "mp4", "video/webm" to "webm", "video/3gpp" to "3gp",
        "video/x-matroska" to "mkv", "video/quicktime" to "mov"
    )
    private val NAME = Regex("vid_[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(mp4|webm|3gp|mkv|mov)")

    /** A still frame from a video, [timeMs] from its start. */
    class Frame(val timeMs: Long, val jpeg: ByteArray)

    /**
     * A picked video, copied into Aura's storage but not yet given to a message: [file] is a
     * temporary copy that [save] turns into the message's video, or [discard] removes.
     */
    class Prepared(val file: File, val extension: String, val durationMs: Long, val frames: List<Frame>) {
        fun discard() {
            file.delete()
        }
    }

    fun isValidName(name: String) = NAME.matches(name)

    fun isVideoType(mimeType: String?) = mimeType?.lowercase()?.startsWith("video/") == true

    fun dir(context: Context): File = File(context.filesDir, DIR).apply { mkdirs() }

    fun file(context: Context, name: String): File? =
        if (isValidName(name)) File(dir(context), name).takeIf { it.isFile } else null

    /** When to take each still frame: evenly spread, about one per [MS_PER_FRAME], at most [MAX_FRAMES]. */
    fun frameTimes(durationMs: Long): List<Long> {
        if (durationMs <= 0) return listOf(0L)
        val count = (durationMs / MS_PER_FRAME).toInt().coerceIn(1, MAX_FRAMES)
        return (0 until count).map { i -> ((i + 0.5) * durationMs / count).toLong() }
    }

    /** "0:07", "1:05", "1:02:03" */
    fun formatTime(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    /** Copies a picked video into Aura's storage and takes its still frames. Throws IllegalArgumentException with a reason. */
    fun prepare(context: Context, uri: Uri): Prepared {
        val type = context.contentResolver.getType(uri)?.lowercase()
        val extension = EXTENSIONS[type] ?: "mp4"
        val temp = File(dir(context), "vid_${UUID.randomUUID()}.$extension$PART")
        try {
            val input = context.contentResolver.openInputStream(uri) ?: throw IllegalArgumentException("Couldn't open that video.")
            input.use { inp ->
                temp.outputStream().use { out ->
                    val buffer = ByteArray(256 * 1024)
                    var copied = 0L
                    while (true) {
                        val n = inp.read(buffer)
                        if (n < 0) break
                        copied += n
                        if (copied > MAX_INPUT_BYTES) throw IllegalArgumentException("That video is larger than 300 MB.")
                        out.write(buffer, 0, n)
                    }
                }
            }
            return prepareFile(temp, extension)
        } catch (e: Exception) {
            temp.delete()
            throw e
        }
    }

    /** Reads a video file already in Aura's storage: its length and still frames. */
    fun prepareFile(file: File, extension: String): Prepared {
        val retriever = MediaMetadataRetriever()
        try {
            try {
                retriever.setDataSource(file.path)
            } catch (e: RuntimeException) {
                throw IllegalArgumentException("That file isn't a video Aura can read.")
            }
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val frames = frameTimes(duration).mapNotNull { t -> frameAt(retriever, t)?.let { Frame(t, toJpeg(it)) } }
            if (frames.isEmpty()) throw IllegalArgumentException("Couldn't read any pictures from that video.")
            return Prepared(file, extension, duration, frames)
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun frameAt(retriever: MediaMetadataRetriever, timeMs: Long): Bitmap? = runCatching {
        val us = timeMs * 1000
        val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
        val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        // The frame comes back upright; its size before turning has width and height swapped for 90 / 270.
        val (uw, uh) = if (rotation % 180 != 0) h to w else w to h
        if (Build.VERSION.SDK_INT >= 27 && uw > 0 && uh > 0) {
            val (tw, th) = frameSize(uw, uh)
            retriever.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, tw, th)
        } else {
            retriever.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        }
    }.getOrNull()

    /** A frame's size: within [FRAME_LONG_EDGE] on the long side, never larger than the video. */
    fun frameSize(width: Int, height: Int): Pair<Int, Int> {
        val scale = minOf(1.0, FRAME_LONG_EDGE.toDouble() / max(width, height))
        return max(1, (width * scale).roundToInt()) to max(1, (height * scale).roundToInt())
    }

    private fun toJpeg(frame: Bitmap): ByteArray {
        val (w, h) = frameSize(frame.width, frame.height)
        val scaled = if (w == frame.width && h == frame.height) frame else Bitmap.createScaledBitmap(frame, w, h, true)
        return ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, ChatImages.JPEG_QUALITY, it) }.toByteArray()
    }

    /** Gives a prepared video its final name; returns the name to keep with the message. */
    fun save(context: Context, video: Prepared): String {
        val name = "vid_${UUID.randomUUID()}.${video.extension}"
        if (!video.file.renameTo(File(dir(context), name))) {
            video.discard()
            throw IllegalStateException("Couldn't save the video.")
        }
        return name
    }

    /**
     * Deletes stored videos no message refers to any more. Copies still being prepared or waiting to
     * be sent (less than a day old) are left alone. Returns how many were deleted.
     */
    fun deleteUnused(context: Context, inUse: Set<String>, now: Long = System.currentTimeMillis()): Int {
        var removed = 0
        dir(context).listFiles()?.forEach { f ->
            val pending = f.name.endsWith(PART) && now - f.lastModified() < 24 * 60 * 60 * 1000L
            if (f.name !in inUse && !pending && f.delete()) removed++
        }
        return removed
    }

    /** The video's first still frame, small, for showing in the chat; null if it can't be read. */
    fun poster(file: File, maxEdge: Int): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.path)
            val frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
            val scale = minOf(1.0, maxEdge.toDouble() / max(frame.width, frame.height))
            if (scale >= 1.0) frame
            else Bitmap.createScaledBitmap(frame, max(1, (frame.width * scale).roundToInt()), max(1, (frame.height * scale).roundToInt()), true)
        } catch (e: RuntimeException) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** The video's length in milliseconds, or 0 if it can't be read. */
    fun durationOf(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.path)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (e: RuntimeException) {
            0L
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** What Claude is told about the frames of a video: when each was taken, and that there's no sound. */
    fun noteForClaude(durationMs: Long, frames: List<Frame>, picturesBefore: Int): String {
        val times = frames.joinToString(", ") { formatTime(it.timeMs) }
        val which = when {
            frames.size == 1 && picturesBefore == 0 -> "The image is a still frame"
            frames.size == 1 -> "The last image is a still frame"
            picturesBefore == 0 -> "The ${frames.size} images are still frames"
            else -> "The last ${frames.size} images are still frames"
        }
        return "[The user attached a video, ${formatTime(durationMs)} long. $which " +
            "from it, taken at $times. You can't hear its sound or see what happens between the frames.]"
    }
}
