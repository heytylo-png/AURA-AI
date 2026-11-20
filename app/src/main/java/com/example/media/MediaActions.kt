package com.example.media

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.example.data.model.MediaLibrary
import java.io.File

/** Saving pictures and videos to the phone's gallery, and handing them to other apps. */
object MediaActions {
    const val ALBUM = "Aura"

    fun authority(context: Context) = "${context.packageName}.files"

    /** Android 9 and older need the storage permission to save to the gallery; newer versions don't. */
    fun needsStoragePermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < 29 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED

    /**
     * Copies a stored picture or video into the gallery, in an "Aura" album (Pictures/Aura or
     * Movies/Aura). Returns the gallery item's address. The copy is the user's: deleting it in Aura
     * doesn't remove it from the gallery.
     */
    fun saveToGallery(context: Context, file: File): Result<Uri> = runCatching {
        val mime = MediaLibrary.mimeType(file.name)
        val video = mime.startsWith("video/")
        val displayName = "Aura_${System.currentTimeMillis()}.${MediaLibrary.extensionOf(file.name)}"
        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = context.contentResolver
            val collection = if (video) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${if (video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES}/$ALBUM")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(collection, values) ?: throw IllegalStateException("The gallery didn't accept it.")
            try {
                resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                    ?: throw IllegalStateException("Couldn't write to the gallery.")
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
            uri
        } else {
            if (needsStoragePermission(context)) throw SecurityException("Aura needs permission to save to your gallery.")
            @Suppress("DEPRECATION")
            val folder = File(Environment.getExternalStoragePublicDirectory(if (video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES), ALBUM)
            folder.mkdirs()
            val target = File(folder, displayName)
            file.copyTo(target, overwrite = false)
            MediaScannerConnection.scanFile(context, arrayOf(target.path), arrayOf(mime), null)
            Uri.fromFile(target)
        }
    }

    /** A content address another app can read for this one share (Aura's own files only). */
    fun shareUri(context: Context, file: File): Uri = FileProvider.getUriForFile(context, authority(context), file)

    /** The system share sheet for a stored picture or video. */
    fun shareIntent(context: Context, file: File): Intent {
        val send = Intent(Intent.ACTION_SEND)
            .setType(MediaLibrary.mimeType(file.name))
            .putExtra(Intent.EXTRA_STREAM, shareUri(context, file))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, null)
    }
}
