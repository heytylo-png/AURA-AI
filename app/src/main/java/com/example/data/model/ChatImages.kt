package com.example.data.model

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Images attached to chat messages. Everything here runs on the phone.
 *
 * A picked photo is turned upright, shrunk to what Claude reads at full detail, and re-encoded as
 * JPEG. Re-encoding keeps only the pixels, so the photo's metadata (location, camera, time taken)
 * is not in what is stored or sent. Images live in Aura's private storage, one file per message.
 */
object ChatImages {
    /** Claude reads images up to about this many pixels on the long side without shrinking them itself. */
    const val MAX_LONG_EDGE = 1568
    /** ...and up to about this many pixels in total. */
    const val MAX_PIXELS = 1_150_000
    const val JPEG_QUALITY = 85
    /** Largest file accepted from the picker (photos from good cameras can be large). */
    const val MAX_INPUT_BYTES = 40L * 1024 * 1024
    private const val DIR = "chat_images"
    // JPEG for photos; PNG for stickers, which keep their transparent background.
    private val NAME = Regex("img_[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(jpg|png)")

    /** A picked image, ready to store and send: JPEG bytes without metadata. */
    class Prepared(val jpeg: ByteArray, val width: Int, val height: Int)

    fun isValidName(name: String) = NAME.matches(name)

    fun dir(context: Context): File = File(context.filesDir, DIR).apply { mkdirs() }

    fun file(context: Context, name: String): File? =
        if (isValidName(name)) File(dir(context), name).takeIf { it.isFile } else null

    /** The size to shrink to: within [MAX_LONG_EDGE] and [MAX_PIXELS], never larger than the original. */
    fun targetSize(width: Int, height: Int): Pair<Int, Int> {
        require(width > 0 && height > 0)
        val byEdge = MAX_LONG_EDGE.toDouble() / max(width, height)
        val byPixels = sqrt(MAX_PIXELS.toDouble() / (width.toLong() * height))
        val scale = minOf(1.0, byEdge, byPixels)
        return max(1, (width * scale).roundToInt()) to max(1, (height * scale).roundToInt())
    }

    /** Reads a picked image and prepares it. Throws IllegalArgumentException with a readable reason. */
    fun prepare(context: Context, uri: Uri): Prepared {
        val resolver = context.contentResolver
        val bytes = resolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                if (out.size() > MAX_INPUT_BYTES) throw IllegalArgumentException("That image is larger than 40 MB.")
            }
            out.toByteArray()
        } ?: throw IllegalArgumentException("Couldn't open that image.")
        return prepare(bytes)
    }

    fun prepare(bytes: ByteArray): Prepared {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IllegalArgumentException("That file isn't an image Aura can read.")
        }
        // Decode at a reduced size straight away, so a large photo doesn't fill the memory.
        val (roughW, roughH) = targetSize(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= roughW && bounds.outHeight / (sample * 2) >= roughH) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IllegalArgumentException("That image couldn't be read (it may be damaged).")

        val upright = rotateUpright(decoded, orientationOf(bytes))
        val (w, h) = targetSize(upright.width, upright.height)
        val scaled = if (w == upright.width && h == upright.height) upright else Bitmap.createScaledBitmap(upright, w, h, true)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        return Prepared(out.toByteArray(), scaled.width, scaled.height)
    }

    private fun orientationOf(bytes: ByteArray): Int = runCatching {
        ExifInterface(bytes.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    private fun rotateUpright(bitmap: Bitmap, orientation: Int): Bitmap {
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
    }

    /**
     * The picture cut to exactly [width] x [height] (centred, filling the frame) as JPEG, for a video's
     * first frame, which must match the video's size.
     */
    fun fitExactly(bytes: ByteArray, width: Int, height: Int): ByteArray {
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw IllegalArgumentException("That image couldn't be read.")
        val upright = rotateUpright(decoded, orientationOf(bytes))
        val scale = maxOf(width.toFloat() / upright.width, height.toFloat() / upright.height)
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        canvas.drawColor(android.graphics.Color.BLACK)
        val m = Matrix().apply {
            postScale(scale, scale)
            postTranslate((width - upright.width * scale) / 2f, (height - upright.height * scale) / 2f)
        }
        canvas.drawBitmap(upright, m, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
        return ByteArrayOutputStream().also { out.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }.toByteArray()
    }

    /** Stores a prepared image; returns the name to keep with the message. */
    fun save(context: Context, image: Prepared): String {
        val name = "img_${UUID.randomUUID()}.jpg"
        val target = File(dir(context), name)
        val partial = File(dir(context), "$name.part")
        partial.writeBytes(image.jpeg)
        if (!partial.renameTo(target)) {
            partial.delete()
            throw IllegalStateException("Couldn't save the image.")
        }
        return name
    }

    /**
     * Stores a PNG (a sticker) as it is, after checking it decodes and isn't too large; returns the name.
     */
    fun savePng(context: Context, png: ByteArray): String {
        if (png.size > 12 * 1024 * 1024) throw IllegalArgumentException("That sticker is too large.")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(png, 0, png.size, bounds)
        val signature = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)
        val isPng = png.size > signature.size && signature.indices.all { png[it] == signature[it] }
        if (bounds.outWidth <= 0 || !isPng) throw IllegalArgumentException("That isn't a PNG image.")
        val name = "img_${UUID.randomUUID()}.png"
        val partial = File(dir(context), "$name.part")
        partial.writeBytes(png)
        if (!partial.renameTo(File(dir(context), name))) {
            partial.delete()
            throw IllegalStateException("Couldn't save the sticker.")
        }
        return name
    }

    /** Deletes stored images no message refers to any more (chats cleared or deleted). Returns how many. */
    fun deleteUnused(context: Context, inUse: Set<String>): Int {
        var removed = 0
        dir(context).listFiles()?.forEach { f ->
            if (f.name !in inUse && f.delete()) removed++
        }
        return removed
    }

    /** A small version for showing in the chat, or null if the file is gone or unreadable. */
    fun thumbnail(file: File, maxEdge: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
        BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()
}
