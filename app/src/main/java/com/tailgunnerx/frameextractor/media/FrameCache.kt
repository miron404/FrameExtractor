package com.tailgunnerx.frameextractor.media

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import com.tailgunnerx.frameextractor.util.LruCache

/**
 * Keeps the last few decoded *preview* frames around.
 *
 * Frame stepping (and dragging the timeline a hair) very often asks for a frame that was decoded
 * moments ago; re-seeking the decoder for those is pure waste. Full resolution frames are never
 * cached - they are decoded on demand when saving.
 */
class FrameCache(private val maxBytes: Long = DEFAULT_MAX_BYTES) {

    private val cache = LruCache<Key, Bitmap>(maxBytes) { it.byteCount.toLong() }

    fun get(frameIndex: Long, dimension: Int): Bitmap? = cache.get(Key(frameIndex, dimension))

    fun put(frameIndex: Long, dimension: Int, bitmap: Bitmap) {
        cache.put(Key(frameIndex, dimension), bitmap)
    }

    fun clear() = cache.clear()

    val size: Int get() = cache.size

    private data class Key(val frameIndex: Long, val dimension: Int)

    companion object {
        /** ~8 preview frames at 1080p, a rounding error next to the video decoder's own buffers. */
        const val DEFAULT_MAX_BYTES = 48L * 1024L * 1024L
    }
}
