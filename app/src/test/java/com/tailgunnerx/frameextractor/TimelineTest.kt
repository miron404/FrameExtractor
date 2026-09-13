package com.tailgunnerx.frameextractor

import androidx.compose.ui.unit.IntSize
import com.tailgunnerx.frameextractor.util.Timeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineTest {

    @Test
    fun msPerFrame_convertsCommonFrameRates() {
        assertEquals(33L, Timeline.msPerFrame(30f))
        assertEquals(16L, Timeline.msPerFrame(60f))
        assertEquals(40L, Timeline.msPerFrame(25f))
        assertEquals(500L, Timeline.msPerFrame(2f))
    }

    @Test
    fun msPerFrame_neverReachesZeroForAbsurdFrameRates() {
        assertEquals(Timeline.MIN_MS_PER_FRAME, Timeline.msPerFrame(10_000f))
        assertEquals(Timeline.MIN_MS_PER_FRAME, Timeline.msPerFrame(Float.MAX_VALUE))
    }

    @Test
    fun sanitizeFps_fallsBackForBrokenMetadata() {
        assertEquals(Timeline.DEFAULT_FPS, Timeline.sanitizeFps(null))
        assertEquals(Timeline.DEFAULT_FPS, Timeline.sanitizeFps(0f))
        assertEquals(Timeline.DEFAULT_FPS, Timeline.sanitizeFps(-12f))
        assertEquals(Timeline.DEFAULT_FPS, Timeline.sanitizeFps(Float.NaN))
        assertEquals(Timeline.DEFAULT_FPS, Timeline.sanitizeFps(Float.POSITIVE_INFINITY))
        assertEquals(120f, Timeline.sanitizeFps(120f))
        assertEquals(Timeline.MAX_FPS, Timeline.sanitizeFps(10_000f))
    }

    @Test
    fun frameNumberAndIndex_areConsistent() {
        val msPerFrame = Timeline.msPerFrame(30f)
        assertEquals(1L, Timeline.frameNumber(0L, msPerFrame))
        assertEquals(2L, Timeline.frameNumber(33L, msPerFrame))
        assertEquals(2L, Timeline.frameNumber(65L, msPerFrame))
        assertEquals(0L, Timeline.frameIndex(0L, msPerFrame))
        // Positions inside the same frame map to the same frame index, which is what lets the
        // decoder skip re-decoding while the timeline is dragged by a few milliseconds.
        assertEquals(
            Timeline.frameIndex(100L, msPerFrame),
            Timeline.frameIndex(120L, msPerFrame),
        )
    }

    @Test
    fun totalFrames_countsInclusiveEnd() {
        val msPerFrame = Timeline.msPerFrame(30f)
        assertEquals(61L, Timeline.totalFrames(2000L, msPerFrame))
        assertEquals(1L, Timeline.totalFrames(0L, msPerFrame))
    }

    @Test
    fun step_clampsInsideTheClip() {
        val msPerFrame = Timeline.msPerFrame(30f)
        assertEquals(0L, Timeline.step(0L, -5L, msPerFrame, 2000L))
        assertEquals(2000L, Timeline.step(2000L, 5L, msPerFrame, 2000L))
        assertEquals(66L, Timeline.step(33L, 1L, msPerFrame, 2000L))
        assertEquals(0L, Timeline.step(33L, -1L, msPerFrame, 2000L))
    }

    @Test
    fun playbackSpeed_mapsDisplayFpsOntoSourceFps() {
        // 5 fps preview of 30 fps footage is a 6x slow motion.
        assertEquals(5f / 30f, Timeline.playbackSpeed(5f, 30f), 1e-6f)
        assertEquals(1f, Timeline.playbackSpeed(30f, 30f), 1e-6f)
        assertEquals(2f, Timeline.playbackSpeed(60f, 30f), 1e-6f)
    }

    @Test
    fun playbackSpeed_staysInsideTheRangeThePlayerAccepts() {
        assertEquals(Timeline.MIN_PLAYBACK_SPEED, Timeline.playbackSpeed(1f, 240f), 1e-6f)
        assertEquals(Timeline.MAX_PLAYBACK_SPEED, Timeline.playbackSpeed(60f, 1f), 1e-6f)
        assertTrue(Timeline.playbackSpeed(5f, 0f) > 0f)
    }

    @Test
    fun fitWithin_preservesAspectRatioAndSize() {
        assertEquals(IntSize(160, 90), Timeline.fitWithin(1920, 1080, 160))
        // Portrait sources keep their aspect ratio too.
        assertEquals(IntSize(90, 160), Timeline.fitWithin(1080, 1920, 160))
        // Sources smaller than the box are left alone: never upscale a decode.
        assertEquals(IntSize(320, 240), Timeline.fitWithin(320, 240, 1080))
        assertEquals(IntSize(1920, 1080), Timeline.fitWithin(1920, 1080, 1920))
    }

    @Test
    fun fitWithin_survivesMissingMetadata() {
        assertEquals(IntSize(720, 720), Timeline.fitWithin(0, 0, 720))
    }

    @Test
    fun previewDimension_isBoundedByTheViewAndSaneLimits() {
        assertEquals(Timeline.MIN_PREVIEW_DIMENSION, Timeline.previewDimension(IntSize(200, 100)))
        assertEquals(Timeline.MAX_PREVIEW_DIMENSION, Timeline.previewDimension(IntSize(3840, 2160)))
        // A tall phone view is bounded by the cap, not by the width.
        assertEquals(Timeline.MAX_PREVIEW_DIMENSION, Timeline.previewDimension(IntSize(1440, 2960)))
        assertEquals(1440, Timeline.previewDimension(IntSize(1080, 1440)))
        assertEquals(Timeline.FALLBACK_PREVIEW_DIMENSION, Timeline.previewDimension(IntSize.Zero))
    }

    @Test
    fun formatHelpers_areStable() {
        assertEquals("29.9", Timeline.formatFps(29.94f))
        assertEquals("0:02.500", Timeline.formatPosition(2500L))
        assertEquals("1:05.000", Timeline.formatPosition(65_000L))
        assertEquals("0:00.000", Timeline.formatPosition(-5L))
    }
}
