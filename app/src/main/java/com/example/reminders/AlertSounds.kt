package com.example.reminders

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.nio.ByteBuffer

/**
 * A video's sound as the sound of Aura's reminders. The sound is copied out of the video as it is
 * (no re-encoding), cut to [MAX_MS], and added to the phone's notification sounds as "Aura reminder",
 * because Android plays a notification sound only from there.
 */
object AlertSounds {
    const val MAX_MS = 30_000L
    const val TITLE = "Aura reminder"

    /** Android 10 or newer: notification sounds can be added without any permission. */
    val supported get() = Build.VERSION.SDK_INT >= 29

    /**
     * Copies the first [maxMs] of [video]'s sound into [target] (an .m4a file). Throws
     * IllegalArgumentException with a reason when the video has no sound Aura can use.
     */
    fun extractSound(video: File, target: File, maxMs: Long = MAX_MS) {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            try {
                extractor.setDataSource(video.path)
            } catch (e: Exception) {
                throw IllegalArgumentException("That video couldn't be read.")
            }
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IllegalArgumentException("This video has no sound.")
            val format = extractor.getTrackFormat(track)
            if (format.getString(MediaFormat.KEY_MIME) != MediaFormat.MIMETYPE_AUDIO_AAC) {
                throw IllegalArgumentException("This video's sound is in a format Aura can't use for alerts (it needs AAC, as most MP4 videos have).")
            }
            extractor.selectTrack(track)
            muxer = MediaMuxer(target.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val out = muxer.addTrack(format)
            muxer.start()
            val buffer = ByteBuffer.allocate(format.takeIf { it.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE) }
                ?.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)?.coerceAtLeast(64 * 1024) ?: (256 * 1024))
            val info = MediaCodec.BufferInfo()
            var samples = 0
            while (true) {
                info.size = extractor.readSampleData(buffer, 0)
                if (info.size < 0) break
                info.presentationTimeUs = extractor.sampleTime
                if (info.presentationTimeUs > maxMs * 1000) break
                info.offset = 0
                info.flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                muxer.writeSampleData(out, buffer, info)
                samples++
                extractor.advance()
            }
            if (samples == 0) throw IllegalArgumentException("This video's sound is empty.")
            muxer.stop()
        } catch (e: Exception) {
            target.delete()
            throw e
        } finally {
            runCatching { muxer?.release() }
            extractor.release()
        }
    }

    /**
     * Adds [sound] to the phone's notification sounds (Notifications/Aura), replacing the one Aura added
     * before ([previous]). Returns its address, which a notification channel can play.
     */
    fun install(context: Context, sound: File, previous: Uri?): Uri {
        check(supported) { "Custom reminder sounds need Android 10 or newer." }
        val resolver = context.contentResolver
        previous?.let { runCatching { resolver.delete(it, null, null) } }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$TITLE ${System.currentTimeMillis()}.m4a")
            put(MediaStore.MediaColumns.TITLE, TITLE)
            put(MediaStore.MediaColumns.MIME_TYPE, "audio/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_NOTIFICATIONS}/Aura")
            put(MediaStore.Audio.Media.IS_NOTIFICATION, 1)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: throw IllegalStateException("The phone didn't accept the sound.")
        try {
            resolver.openOutputStream(uri)?.use { out -> sound.inputStream().use { it.copyTo(out) } }
                ?: throw IllegalStateException("Couldn't write the sound.")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return uri
    }

    /** Removes the sound Aura added, if any. */
    fun remove(context: Context, uri: Uri) {
        runCatching { context.contentResolver.delete(uri, null, null) }
    }
}
