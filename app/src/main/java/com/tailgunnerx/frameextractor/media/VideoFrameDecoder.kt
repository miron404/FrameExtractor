package com.tailgunnerx.frameextractor.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.tailgunnerx.frameextractor.util.Timeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Reads a clip's metadata, and decodes single frames at native resolution when one is extracted.
 *
 * This is deliberately *not* on the interactive path any more. `MediaMetadataRetriever` seeks by
 * decoding forward from the preceding keyframe every single time, so a one frame step costs a whole
 * GOP - which is why stepping stayed slow no matter how small the bitmaps got. What the user sees is
 * now rendered by the player's own hardware decoder (see `FrameExtractorScreen`), and the retriever
 * is only asked for a frame when one is being saved, where a few hundred milliseconds cost nothing.
 *
 * `MediaMetadataRetriever` is not thread safe, so access is serialised behind a mutex and kept off
 * the main thread.
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

    /** Decodes the frame at [timeMs] at the source's native resolution. */
    suspend fun decodeFrame(timeMs: Long): Bitmap? = withContext(Dispatchers.IO) {
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

    companion object {

        /**
         * Opens [uri] and reads its metadata. Blocking work happens on the IO dispatcher so the
         * picker callback never stalls the frame that is being drawn.
         */
        suspend fun open(context: Context, uri: Uri): VideoFrameDecoder {
            // The retriever is constructed here rather than inside withContext on purpose. If the
            // caller is cancelled while the IO block is running, withContext discards whatever the
            // block returned and throws instead - which, with the retriever created in there, would
            // strand an open one. Opening a clip and immediately picking another one is exactly the
            // case that does this.
            val retriever = MediaMetadataRetriever()
            try {
                return withContext(Dispatchers.IO) {
                    retriever.setDataSource(context.applicationContext, uri)
                    VideoFrameDecoder(retriever, readInfo(retriever))
                }
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
