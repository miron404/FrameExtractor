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

    /**
     * Index of the frame whose interval contains [positionMs].
     *
     * For an arbitrary instant - the media clock, a scrub target. For a frame's *own* timestamp use
     * [frameIndexOfTimestampUs]; rounding down is wrong there.
     */
    fun frameIndexAt(positionMs: Long, fps: Float): Long {
        if (positionMs <= 0L) return 0L
        val rate = sanitizeFps(fps).toDouble()
        return floor(positionMs * rate / 1000.0).toLong().coerceAtLeast(0L)
    }

    /**
     * Index of the frame a *frame timestamp* belongs to, as reported by the renderer in microseconds.
     *
     * Not the same question as [frameIndexAt], and not answered the same way. That one is given an
     * arbitrary instant and asks which frame's interval contains it, so it rounds down. This one is
     * given a frame's own presentation timestamp, which a container stores truncated onto its time
     * base and so sits a hair *below* the ideal boundary - 966666us for a frame that ideally starts
     * at 966666.67us. Rounding down there lands one frame early; rounding to nearest is both correct
     * and tolerant of the timebase, since a stored timestamp is off by at most half a tick while a
     * frame lasts thousands of them.
     */
    fun frameIndexOfTimestampUs(presentationTimeUs: Long, fps: Float): Long {
        if (presentationTimeUs <= 0L) return 0L
        val rate = sanitizeFps(fps).toDouble()
        return (presentationTimeUs * rate / 1_000_000.0).roundToLong().coerceAtLeast(0L)
    }

    /**
     * Where to aim to land on frame [index] - for the player and for the frame extractor alike.
     *
     * The two get there differently, and the target has to satisfy both:
     *
     * * The **player** renders the first frame whose timestamp is at or after the seek position.
     *   (Not the last one at or before it. Aiming at the middle of the frame's interval on that
     *   assumption selected the *next* frame every time, and for the final frame there was no frame
     *   at or after the target at all, so nothing was rendered.)
     * * `MediaMetadataRetriever.getFrameAtTime(.., OPTION_CLOSEST)` picks the frame whose timestamp
     *   is *nearest* the target.
     *
     * So the target must sit just below frame [index]'s own timestamp: at or before it, after the
     * previous frame's, and nearer to this one than to either neighbour. A quarter of a frame early
     * satisfies all of that with room for the frame rate being a slight estimate - it is derived
     * from the container's frame count and duration, and an error there accumulates over a long
     * clip until a target computed from it crosses a frame boundary.
     */
    fun seekTargetMs(index: Long, fps: Float): Long {
        if (index <= 0L) return 0L
        val rate = sanitizeFps(fps).toDouble()
        val quarterFrameMs = 250.0 / rate
        return floor(index * 1000.0 / rate - quarterFrameMs).toLong().coerceAtLeast(0L)
    }

    /** Frame [index]'s own timestamp. Shown in the position readout; to seek, use [seekTargetMs]. */
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
