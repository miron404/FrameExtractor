package com.tailgunnerx.frameextractor.media

import com.tailgunnerx.frameextractor.util.Timeline

/**
 * Everything we know about the loaded clip, read once through [android.media.MediaMetadataRetriever]
 * instead of on every interaction.
 *
 * @param displayWidth/displayHeight resolution as the viewer sees it, i.e. rotation already applied.
 * @param frameCount frame count reported by the container, or null when it does not report one.
 */
data class VideoInfo(
    val durationMs: Long,
    val displayWidth: Int,
    val displayHeight: Int,
    val rotationDegrees: Int,
    val fps: Float,
    val frameCount: Long?,
) {
    val hasSize: Boolean = displayWidth > 0 && displayHeight > 0

    /** Total frames, preferring the container's own count over one derived from duration x fps. */
    val totalFrames: Long = frameCount?.takeIf { it > 0L } ?: Timeline.frameCount(durationMs, fps)

    val lastFrameIndex: Long = (totalFrames - 1L).coerceAtLeast(0L)

    val resolutionLabel: String = if (hasSize) "${displayWidth}x$displayHeight" else "Unknown"

    val fpsLabel: String = "${Timeline.formatFps(fps)} FPS"

    companion object {
        val EMPTY = VideoInfo(0L, 0, 0, 0, Timeline.DEFAULT_FPS, null)
    }
}
