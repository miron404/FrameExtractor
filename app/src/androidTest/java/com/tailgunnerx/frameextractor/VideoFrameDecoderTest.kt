package com.tailgunnerx.frameextractor

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tailgunnerx.frameextractor.media.VideoFrameDecoder
import com.tailgunnerx.frameextractor.util.Timeline
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * Exercises the extraction path against a real clip.
 *
 * This decoder is no longer on the interactive path - what the user sees is rendered by the player -
 * so what matters here is that the frame written to disk is the frame that was asked for, at full
 * resolution.
 */
@RunWith(AndroidJUnit4::class)
class VideoFrameDecoderTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun open_readsContainerMetadata() = runBlocking {
        val decoder = VideoFrameDecoder.open(context, TestVideo.copyToCache())
        try {
            val info = decoder.info
            assertTrue("duration was ${info.durationMs}", abs(info.durationMs - TestVideo.DURATION_MS) <= 100L)
            assertEquals(TestVideo.WIDTH, info.displayWidth)
            assertEquals(TestVideo.HEIGHT, info.displayHeight)
            assertTrue("fps was ${info.fps}", abs(info.fps - TestVideo.FPS) <= 1.5f)
            assertEquals(TestVideo.FRAME_COUNT.toLong(), info.totalFrames)
            assertEquals(TestVideo.FRAME_COUNT.toLong() - 1L, info.lastFrameIndex)
        } finally {
            decoder.close()
        }
    }

    @Test
    fun decodeFrame_usesTheSourceResolution() = runBlocking {
        val decoder = VideoFrameDecoder.open(context, TestVideo.copyToCache())
        try {
            val frame = decoder.decodeFrame(TestVideo.midpointOf(3))
            assertNotNull(frame)
            assertEquals(TestVideo.WIDTH, frame!!.width)
            assertEquals(TestVideo.HEIGHT, frame.height)
        } finally {
            decoder.close()
        }
    }

    /**
     * The end-to-end check that "frame N" means the same thing to the app as it does to the file.
     *
     * Deliberately goes through [Timeline.frameMidpointMs] and the fps the app read from the
     * container, rather than computing a timestamp locally: the previous version of this test did
     * its own arithmetic in doubles and so could not see that the app itself was stepping by a
     * truncated integer number of milliseconds and drifting a frame every thirty.
     */
    @Test
    fun everyFrameIndexExtractsThatExactFrame() = runBlocking {
        val decoder = VideoFrameDecoder.open(context, TestVideo.copyToCache())
        val mismatches = mutableListOf<Pair<Long, Int>>()
        try {
            for (index in 0L until decoder.info.totalFrames) {
                val timeMs = Timeline.frameMidpointMs(index, decoder.info.fps)
                val frame = decoder.decodeFrame(timeMs)
                assertNotNull("no frame decoded for index $index (${timeMs}ms)", frame)
                val decoded = TestVideo.barcodeIndexOf(frame!!)
                if (decoded.toLong() != index) mismatches += index to decoded
            }
        } finally {
            decoder.close()
        }
        assertTrue(
            "asked for frames 0..${TestVideo.FRAME_COUNT - 1} and got ${mismatches.size} wrong: $mismatches",
            mismatches.isEmpty(),
        )
    }

    /** The last frame has to be reachable: truncated frame arithmetic used to make it unreachable. */
    @Test
    fun theLastFrameIsReachable() = runBlocking {
        val decoder = VideoFrameDecoder.open(context, TestVideo.copyToCache())
        try {
            val last = decoder.info.lastFrameIndex
            val frame = decoder.decodeFrame(Timeline.frameMidpointMs(last, decoder.info.fps))
            assertNotNull(frame)
            assertEquals(last, TestVideo.barcodeIndexOf(frame!!).toLong())
        } finally {
            decoder.close()
        }
    }

    @Test
    fun repeatedSeeksAreDeterministic() = runBlocking {
        val decoder = VideoFrameDecoder.open(context, TestVideo.copyToCache())
        try {
            val time = Timeline.frameMidpointMs(17L, decoder.info.fps)
            val first = decoder.decodeFrame(time)
            val second = decoder.decodeFrame(time)
            assertNotNull(first)
            assertNotNull(second)
            assertEquals(TestVideo.barcodeIndexOf(first!!), TestVideo.barcodeIndexOf(second!!))
        } finally {
            decoder.close()
        }
    }

    @Test
    fun decodeAfterCloseReturnsNothing() = runBlocking {
        val decoder = VideoFrameDecoder.open(context, TestVideo.copyToCache())
        decoder.close()
        var frame = decoder.decodeFrame(0L)
        repeat(20) {
            if (frame == null) return@repeat
            delay(50)
            frame = decoder.decodeFrame(0L)
        }
        assertNull("a closed decoder still produced frames", frame)
    }
}
