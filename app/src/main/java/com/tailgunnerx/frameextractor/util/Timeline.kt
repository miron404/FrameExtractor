package com.tailgunnerx.frameextractor.util

import java.util.Locale
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * Frame <-> time arithmetic and playback speed mapping.
 *
 * The frame *index* is the unit the app thinks in; milliseconds are only ever derived from it.
 * Doing it the other way around - keeping an integer "milliseconds per frame" and stepping by it -
 * silently drifts, because no interesting frame rate divides 1000 evenly: 30 fps truncates to 33 ms
 * and loses a frame every 30, so on a ten minute clip the last six seconds become unreachable and
 * the frame counter is off by 180. Everything here is computed from the rational frame rate instead.
 *
 * Pure functions with no Android dependencies so they can be unit tested on the JVM.
 */
object Timeline {

    const val DEFAULT_FPS = 30f
    const val MIN_FPS = 1f
    const val MAX_FPS = 240f

    /** Slowest/fastest playback speed we ask the video player for. */
    const val MIN_PLAYBACK_SPEED = 0.05f
    const val MAX_PLAYBACK_SPEED = 8f

    /** Keeps metadata from broken containers (0 fps, NaN, absurd values) usable. */
    fun sanitizeFps(fps: Float?): Float =
        if (fps == null || !fps.isFinite() || fps <= 0f) DEFAULT_FPS else fps.coerceIn(MIN_FPS, MAX_FPS)

    /** Index of the frame on screen at [positionMs]. */
    fun frameIndexAt(positionMs: Long, fps: Float): Long {
        if (positionMs <= 0L) return 0L
        val rate = sanitizeFps(fps).toDouble()
        return floor(positionMs * rate / 1000.0).toLong().coerceAtLeast(0L)
    }

    /**
     * Seek target for the **player**: the middle of frame [index]'s interval.
     *
     * A position seek renders the last frame whose timestamp is at or before the target, so the
     * target has to sit inside `[start of frame, start of next frame)`. Aiming at the boundary
     * itself is a coin flip once timestamps are rounded to whole milliseconds - a target of 33 ms
     * for a frame that actually starts at 33.33 ms renders the *previous* frame.
     *
     * Do not use this to extract a frame; see [frameStartMs] for why.
     */
    fun frameMidpointMs(index: Long, fps: Float): Long {
        if (index <= 0L) return ((0.5 * 1000.0) / sanitizeFps(fps).toDouble()).toLong()
        val rate = sanitizeFps(fps).toDouble()
        return ((index + 0.5) * 1000.0 / rate).toLong()
    }

    /**
     * Frame [index]'s own timestamp: what the position readout shows, and the target to use when
     * **extracting** a frame.
     *
     * `MediaMetadataRetriever.getFrameAtTime(.., OPTION_CLOSEST)` picks the frame whose *timestamp*
     * is nearest to the target - it does not ask which frame's interval the target falls in. The
     * midpoint from [frameMidpointMs] is therefore exactly equidistant between this frame's
     * timestamp and the next one, and the platform breaks that tie upwards: at 30 fps that silently
     * extracted the wrong frame for every index where the midpoint landed on a whole millisecond
     * (1, 4, 7, ... - one frame in three). Landing just under the frame's own timestamp, which is
     * what the truncation here does, is off by at most a millisecond against half a frame of
     * tolerance.
     */
    fun frameStartMs(index: Long, fps: Float): Long {
        if (index <= 0L) return 0L
        return (index * 1000.0 / sanitizeFps(fps).toDouble()).toLong()
    }

    /** How long a single frame lasts, in milliseconds. */
    fun frameDurationMs(fps: Float): Long =
        (1000.0 / sanitizeFps(fps).toDouble()).toLong().coerceAtLeast(1L)

    /** Number of frames in a clip of [durationMs]; always at least one. */
    fun frameCount(durationMs: Long, fps: Float): Long {
        if (durationMs <= 0L) return 1L
        val rate = sanitizeFps(fps).toDouble()
        return (durationMs * rate / 1000.0).roundToLong().coerceAtLeast(1L)
    }

    /** Human facing frame number: 1-based, so "Frame 1" is the first frame. */
    fun frameNumber(index: Long): Long = index + 1L

    /**
     * Playback speed that shows [displayFps] frames per second of a [videoFps] source.
     * E.g. 5 fps on 30 fps footage => 0.167x, which is what the slow-motion scrubber shows.
     */
    fun playbackSpeed(displayFps: Float, videoFps: Float): Float {
        if (videoFps <= 0f || !videoFps.isFinite() || !displayFps.isFinite()) return 1f
        return (displayFps / videoFps).coerceIn(MIN_PLAYBACK_SPEED, MAX_PLAYBACK_SPEED)
    }

    /** Aspect ratio a video of [width]x[height] with non-square pixels should be drawn at. */
    fun displayAspectRatio(width: Int, height: Int, pixelWidthHeightRatio: Float): Float {
        if (width <= 0 || height <= 0) return 0f
        val par = if (pixelWidthHeightRatio.isFinite() && pixelWidthHeightRatio > 0f) {
            pixelWidthHeightRatio
        } else {
            1f
        }
        return width * par / height
    }

    fun formatFps(fps: Float): String = String.format(Locale.US, "%.1f", fps)

    fun formatPosition(positionMs: Long): String {
        val safe = positionMs.coerceAtLeast(0L)
        val totalSeconds = safe / 1000L
        return String.format(Locale.US, "%d:%02d.%03d", totalSeconds / 60L, totalSeconds % 60L, safe % 1000L)
    }
}
