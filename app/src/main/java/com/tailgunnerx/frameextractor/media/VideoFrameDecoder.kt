package com.tailgunnerx.frameextractor.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import androidx.compose.ui.unit.IntSize
import com.tailgunnerx.frameextractor.util.Timeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Owns a [MediaMetadataRetriever] for one video source and serialises access to it.
 *
 * `MediaMetadataRetriever` is **not** thread safe, and every method on it blocks for tens to
 * hundreds of milliseconds while the decoder seeks. All of that now happens off the main thread,
 * behind a mutex, and callers can cancel a request simply by cancelling their coroutine - which is
 * what makes scrubbing feel responsive instead of queueing up stale frames.
 */
class VideoFrameDecoder private constructor(
    private val retriever: MediaMetadataRetriever,
    val info: VideoInfo,
) {

    private val mutex = Mutex()

    /** Outlives callers: [close] must be able to wait for an in-flight decode even if the caller's
     *  coroutine scope has already been cancelled. */
    private val closeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var closed = false

    /**
     * Decodes the frame closest to [timeMs] scaled to fit a [maxDimensionPx] box.
     *
     * Decoding at view resolution instead of source resolution is the single biggest win for
     * scrubbing: it avoids allocating, colour converting and uploading huge bitmaps for a view
     * that cannot show them.
     */
    suspend fun decodePreview(timeMs: Long, maxDimensionPx: Int): Bitmap? = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (closed) return@withLock null
            val target = previewTargetSize(maxDimensionPx)
            decodeScaled(timeMs, target.width, target.height)
        }
    }

    /** Decodes at the source's native resolution. Used when saving a frame, never for previews. */
    suspend fun decodeFull(timeMs: Long): Bitmap? = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (closed) return@withLock null
            runCatching {
                retriever.getFrameAtTime(timeMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST)
            }.getOrNull()
        }
    }

    fun close() {
        closeScope.launch {
            mutex.withLock {
                if (!closed) {
                    closed = true
                    runCatching { retriever.release() }
                }
            }
        }
    }

    private fun previewTargetSize(maxDimensionPx: Int): IntSize =
        if (info.hasSize) {
            Timeline.fitWithin(info.displayWidth, info.displayHeight, maxDimensionPx)
        } else {
            IntSize(maxDimensionPx, maxDimensionPx)
        }

    private fun decodeScaled(timeMs: Long, width: Int, height: Int): Bitmap? {
        val timeUs = timeMs * 1000L
        // getScaledFrameAtTime() decodes and scales in one pass; it needs API 27. Below that we
        // fall back to a full size frame and shrink it ourselves.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            val scaled = runCatching {
                retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST, width, height)
            }.getOrNull()
            if (scaled != null) return scaled
        }
        val full = runCatching {
            retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
        }.getOrNull() ?: return null
        if (full.width <= width && full.height <= height) return full
        return runCatching {
            Bitmap.createScaledBitmap(full, width, height, true)
        }.getOrNull() ?: full
    }

    companion object {

        /**
         * Opens [uri] and reads its metadata. Blocking work happens on the IO dispatcher so the
         * picker callback never stalls the frame that is being drawn.
         */
        suspend fun open(context: Context, uri: Uri): VideoFrameDecoder = withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context.applicationContext, uri)
                VideoFrameDecoder(retriever, readInfo(retriever))
            } catch (t: Throwable) {
                runCatching { retriever.release() }
                throw t
            }
        }

        private fun readInfo(retriever: MediaMetadataRetriever): VideoInfo {
            fun metadata(key: Int): String? = runCatching { retriever.extractMetadata(key) }.getOrNull()

            val durationMs = metadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val rawWidth = metadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val rawHeight = metadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rotation = metadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0

            val rotated = rotation % 180 != 0
            val displayWidth = if (rotated) rawHeight else rawWidth
            val displayHeight = if (rotated) rawWidth else rawHeight

            val frameCount = metadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)
                ?.toLongOrNull()
                ?.takeIf { it > 0L }

            val fps = when {
                frameCount != null && durationMs > 0L -> frameCount * 1000f / durationMs.toFloat()
                else -> metadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull()
            }

            return VideoInfo(
                durationMs = durationMs,
                displayWidth = displayWidth,
                displayHeight = displayHeight,
                rotationDegrees = rotation,
                fps = Timeline.sanitizeFps(fps),
                frameCount = frameCount,
            )
        }
    }
}
