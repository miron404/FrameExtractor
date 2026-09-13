package com.tailgunnerx.frameextractor.util

import androidx.compose.ui.unit.IntSize
import java.util.Locale

/**
 * Frame <-> time arithmetic and playback speed mapping.
 *
 * Pure functions with no Android dependencies so they can be unit tested on the JVM.
 */
object Timeline {

    const val DEFAULT_FPS = 30f
    const val MIN_FPS = 1f
    const val MAX_FPS = 240f
    const val MIN_MS_PER_FRAME = 1L

    /** Slowest/fastest playback speed we ask the video player for. */
    const val MIN_PLAYBACK_SPEED = 0.05f
    const val MAX_PLAYBACK_SPEED = 8f

    /**
     * Bounds for the resolution of decoded *preview* frames. Decoding at display resolution instead
     * of the source resolution is by far the cheapest way to cut per-frame work: a 4K source is
     * 8 MB per frame to allocate, convert and upload, while a preview only needs to fill a view.
     */
    const val MIN_PREVIEW_DIMENSION = 720
    const val MAX_PREVIEW_DIMENSION = 1920

    /** Fallback preview size before the viewer has been measured. */
    const val FALLBACK_PREVIEW_DIMENSION = 1080

    fun msPerFrame(fps: Float): Long =
        if (fps.isFinite() && fps >= MIN_FPS) {
            (1000f / fps).toLong().coerceAtLeast(MIN_MS_PER_FRAME)
        } else {
            (1000f / DEFAULT_FPS).toLong()
        }

    /** Keeps metadata from broken containers (0 fps, NaN, absurd values) usable. */
    fun sanitizeFps(fps: Float?): Float =
        if (fps == null || !fps.isFinite() || fps <= 0f) DEFAULT_FPS else fps.coerceIn(MIN_FPS, MAX_FPS)

    fun frameIndex(positionMs: Long, msPerFrame: Long): Long =
        if (msPerFrame <= 0L) 0L else (positionMs / msPerFrame).coerceAtLeast(0L)

    fun frameNumber(positionMs: Long, msPerFrame: Long): Long = frameIndex(positionMs, msPerFrame) + 1L

    fun totalFrames(durationMs: Long, msPerFrame: Long): Long =
        if (msPerFrame <= 0L) 1L else (durationMs / msPerFrame).coerceAtLeast(0L) + 1L

    /** Moves [delta] frames from [positionMs], staying inside the clip. */
    fun step(positionMs: Long, delta: Long, msPerFrame: Long, durationMs: Long): Long =
        (positionMs + delta * msPerFrame).coerceIn(0L, durationMs.coerceAtLeast(0L))

    /**
     * Playback speed that shows [displayFps] frames per second of a [videoFps] source.
     * E.g. 5 fps on 30 fps footage => 0.167x, which is what the slow-motion scrubber shows.
     */
    fun playbackSpeed(displayFps: Float, videoFps: Float): Float {
        if (videoFps <= 0f || !videoFps.isFinite() || !displayFps.isFinite()) return 1f
        return (displayFps / videoFps).coerceIn(MIN_PLAYBACK_SPEED, MAX_PLAYBACK_SPEED)
    }

    /** Largest box with the aspect ratio of [width]x[height] that fits in a [maxDimension] square. */
    fun fitWithin(width: Int, height: Int, maxDimension: Int): IntSize {
        if (width <= 0 || height <= 0 || maxDimension <= 0) return IntSize(maxDimension, maxDimension)
        if (width <= maxDimension && height <= maxDimension) return IntSize(width, height)
        val scale = maxDimension.toFloat() / maxOf(width, height).toFloat()
        return IntSize(
            (width * scale).toInt().coerceAtLeast(1),
            (height * scale).toInt().coerceAtLeast(1),
        )
    }

    /** Preview decode box for a viewer measured at [viewerSize] pixels. */
    fun previewDimension(viewerSize: IntSize): Int {
        val longestSide = maxOf(viewerSize.width, viewerSize.height)
        if (longestSide <= 0) return FALLBACK_PREVIEW_DIMENSION
        return longestSide.coerceIn(MIN_PREVIEW_DIMENSION, MAX_PREVIEW_DIMENSION)
    }

    fun formatFps(fps: Float): String = String.format(Locale.US, "%.1f", fps)

    fun formatPosition(positionMs: Long): String {
        val totalSeconds = (positionMs.coerceAtLeast(0L)) / 1000L
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        val millis = positionMs.coerceAtLeast(0L) % 1000L
        return String.format(Locale.US, "%d:%02d.%03d", minutes, seconds, millis)
    }
}
