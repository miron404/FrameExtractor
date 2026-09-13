package com.tailgunnerx.frameextractor

import com.tailgunnerx.frameextractor.util.Timeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class TimelineTest {

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
    fun frameIndexAt_mapsEveryPositionInsideAFrameOntoThatFrame() {
        // 30 fps: frame 0 covers [0, 33.3), frame 1 covers [33.3, 66.6), ...
        assertEquals(0L, Timeline.frameIndexAt(0L, 30f))
        assertEquals(0L, Timeline.frameIndexAt(33L, 30f))
        assertEquals(1L, Timeline.frameIndexAt(34L, 30f))
        assertEquals(1L, Timeline.frameIndexAt(66L, 30f))
        assertEquals(2L, Timeline.frameIndexAt(67L, 30f))
        assertEquals(0L, Timeline.frameIndexAt(-100L, 30f))
    }

    @Test
    fun theSeekTargetSelectsItsOwnFrameWhicheverWayTheSeekWorks() {
        // Both consumers have to land on frame N from this one target, and they get there by
        // different rules: the player renders the first frame at or after it, the extractor picks
        // the frame with the nearest timestamp. Enshrining only one of those is how a seek to
        // frame 29 ended up rendering frame 30.
        for (fps in listOf(23.976f, 24f, 25f, 29.97f, 30f, 50f, 59.94f, 60f, 120f)) {
            for (index in 0L..300L) {
                val target = Timeline.seekTargetMs(index, fps).toDouble()
                val own = index * 1000.0 / fps
                val previous = (index - 1L) * 1000.0 / fps
                val next = (index + 1L) * 1000.0 / fps
                val where = "fps=$fps index=$index target=$target own=$own"

                // First frame at or after the target must be this one.
                assertTrue("$where: target is past its own frame", target <= own)
                assertTrue("$where: target is not past the previous frame", index == 0L || target > previous)

                // Nearest timestamp to the target must also be this one.
                assertTrue("$where: nearer the next frame", abs(target - own) < abs(target - next))
                assertTrue(
                    "$where: nearer the previous frame",
                    index == 0L || abs(target - own) < abs(target - previous),
                )
            }
        }
    }

    @Test
    fun steppingDoesNotDriftOverALongClip() {
        // The regression this replaces: an integer 33 ms per frame loses a frame every 30, so on a
        // ten minute 30 fps clip the counter ended up 180 frames short and the last six seconds were
        // unreachable. Walking the whole clip has to land exactly on the last frame.
        val fps = 30f
        val durationMs = 10L * 60L * 1000L
        val lastFrame = Timeline.frameCount(durationMs, fps) - 1L
        assertEquals(17_999L, lastFrame)

        // The last frame starts one frame before the end, and is reachable.
        val lastFrameStart = Timeline.frameStartMs(lastFrame, fps)
        assertEquals(599_966L, lastFrameStart)
        assertTrue(
            "the last frame starts ${durationMs - lastFrameStart}ms before the end",
            durationMs - lastFrameStart <= Timeline.frameDurationMs(fps) + 1L,
        )
        assertTrue(
            "the seek target for the last frame falls outside the clip",
            Timeline.seekTargetMs(lastFrame, fps) < durationMs,
        )
    }

    @Test
    fun frameCount_matchesDurationTimesFrameRate() {
        assertEquals(60L, Timeline.frameCount(2000L, 30f))
        assertEquals(18_000L, Timeline.frameCount(600_000L, 30f))
        assertEquals(1L, Timeline.frameCount(0L, 30f))
        // 29.97 fps for exactly ten minutes of NTSC video.
        assertEquals(17_982L, Timeline.frameCount(600_000L, 29.97f))
    }

    @Test
    fun frameStartMs_isTheFramesOwnTimestamp() {
        assertEquals(0L, Timeline.frameStartMs(0L, 30f))
        assertEquals(33L, Timeline.frameStartMs(1L, 30f))
        assertEquals(1000L, Timeline.frameStartMs(30L, 30f))
        assertEquals(599_966L, Timeline.frameStartMs(17_999L, 30f))
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
    fun displayAspectRatio_appliesPixelAspectAndSurvivesMissingMetadata() {
        assertEquals(16f / 9f, Timeline.displayAspectRatio(1920, 1080, 1f), 1e-6f)
        assertEquals(9f / 16f, Timeline.displayAspectRatio(1080, 1920, 1f), 1e-6f)
        // Anamorphic NTSC DVD: 720x480 stored pixels at a 32:27 pixel aspect, displayed 16:9.
        assertEquals(16f / 9f, Timeline.displayAspectRatio(720, 480, 32f / 27f), 1e-4f)
        // An unreported pixel aspect must not collapse the ratio to zero or infinity.
        assertEquals(16f / 9f, Timeline.displayAspectRatio(1920, 1080, 0f), 1e-6f)
        assertEquals(16f / 9f, Timeline.displayAspectRatio(1920, 1080, Float.NaN), 1e-6f)
        assertEquals(0f, Timeline.displayAspectRatio(0, 0, 1f), 1e-6f)
    }

    @Test
    fun formatHelpers_areStable() {
        assertEquals("29.9", Timeline.formatFps(29.94f))
        assertEquals("0:02.500", Timeline.formatPosition(2500L))
        assertEquals("1:05.000", Timeline.formatPosition(65_000L))
        assertEquals("0:00.000", Timeline.formatPosition(-5L))
    }
}
