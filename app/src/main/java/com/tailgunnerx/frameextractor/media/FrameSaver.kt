package com.tailgunnerx.frameextractor.media

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Writes a frame to the shared Pictures collection as a lossless PNG.
 *
 * The frame is decoded again at the video's native resolution here: the preview bitmap shown on
 * screen is intentionally scaled down, so saving it would silently downgrade "extract at original
 * resolution".
 */
object FrameSaver {

    private const val ALBUM = "FrameExtractor"

    suspend fun saveFrame(context: Context, decoder: VideoFrameDecoder, timeMs: Long): Uri {
        val bitmap = decoder.decodeFull(timeMs)
            ?: throw IOException("Could not decode a frame at ${timeMs}ms")
        val fileName = "ExtractedFrame_${timeMs}ms.png"
        return withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                writeViaMediaStore(context, bitmap, fileName)
            } else {
                writeToPublicPictures(context, bitmap, fileName)
            }
        }
    }

    /** Scoped storage path (API 29+): no permission needed, no manual media scan. */
    private fun writeViaMediaStore(context: Context, bitmap: Bitmap, fileName: String): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$ALBUM")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Failed to create a MediaStore entry")
        try {
            compressor(bitmap, uri, resolver)
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
        return uri
    }

    /** Legacy path (API 24-28): needs WRITE_EXTERNAL_STORAGE, then a media scan to appear. */
    private fun writeToPublicPictures(context: Context, bitmap: Bitmap, fileName: String): Uri {
        val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), ALBUM)
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("Could not create ${directory.absolutePath}")
        }
        val file = File(directory, fileName)
        FileOutputStream(file).use { out ->
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                throw IOException("Failed to encode ${file.name}")
            }
        }
        MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("image/png"), null)
        return Uri.fromFile(file)
    }

    private fun compressor(
        bitmap: Bitmap,
        uri: Uri,
        resolver: android.content.ContentResolver,
    ) {
        val out = resolver.openOutputStream(uri) ?: throw IOException("Failed to open $uri")
        out.use {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) {
                throw IOException("Failed to encode the frame")
            }
        }
    }
}
