package com.tailgunnerx.frameextractor

import android.graphics.ImageFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tailgunnerx.frameextractor.ui.VideoPlayerState
import com.tailgunnerx.frameextractor.util.Timeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Covers the interactive path, which is now the player rather than the frame extractor.
 *
 * The test clip has a keyframe only every 30 frames, so frame 45 sits deep inside a GOP - exactly
 * the case that used to break: the player was configured to seek to the nearest *sync* sample, so
 * starting playback from a frame like that silently jumped backwards to frame 30.
 */
@RunWith(AndroidJUnit4::class)
class VideoPlayerStateTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private lateinit var state: VideoPlayerState
    private lateinit var frameSink: ImageReader
    private lateinit var frameSinkThread: HandlerThread

    private fun <T> onMain(block: () -> T): T {
        var result: T? = null
        var thrown: Throwable? = null
        instrumentation.runOnMainSync {
            try {
                result = block()
            } catch (t: Throwable) {
                thrown = t
            }
        }
        thrown?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun waitUntil(message: String, timeoutMs: Long = 15_000L, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (onMain(condition)) return
            onMain { state.playbackError }?.let { error ->
                // An emulator without a working H.264 decoder fails every one of these tests; say so
                // instead of leaving twenty identical timeouts to interpret.
                throw AssertionError("player failed while waiting for $message: $error")
            }
            SystemClock.sleep(50L)
        }
        throw AssertionError("timed out waiting for: $message")
    }

    @Before
    fun setUp() {
        // A real output surface, not because anything looks at it, but because without one ExoPlayer
        // *skips* video buffers instead of rendering them - so nothing is ever put on a surface, and
        // the frame metadata these tests read is never reported. An ImageReader is the headless
        // equivalent of the TextureView the app uses; its frames have to be consumed or the codec
        // stalls once the queue fills.
        frameSinkThread = HandlerThread("frame-sink").apply { start() }
        frameSink = ImageReader.newInstance(TestVideo.WIDTH, TestVideo.HEIGHT, ImageFormat.PRIVATE, 4)
        frameSink.setOnImageAvailableListener(
            { reader -> reader.acquireLatestImage()?.close() },
            Handler(frameSinkThread.looper),
        )

        state = onMain { VideoPlayerState(ExoPlayer.Builder(context.applicationContext).build()) }
        onMain { state.player.setVideoSurface(frameSink.surface) }
        val uri = TestVideo.copyToCache()
        val error = runBlocking(Dispatchers.Main) { state.load(context, uri) }
        assertEquals(null, error)
        waitUntil("player to become ready") { state.player.playbackState == ExoPlayer.STATE_READY }
        assertEquals(null, state.playbackError)
    }

    @After
    fun tearDown() {
        onMain { state.release() }
        frameSink.close()
        frameSinkThread.quitSafely()
    }

    @Test
    fun theFirstFrameReachesTheSurface() {
        // Everything below reads which frame is on the surface, so prove the surface gets frames at
        // all: a failure here means the emulator is skipping video, not that the app is wrong.
        waitUntil("the first frame to be rendered") { state.renderedFrameIndex != null }
    }

    @Test
    fun loadReportsTheClipsFrameCount() {
        assertNotNull(state.info)
        assertEquals(TestVideo.FRAME_COUNT.toLong(), state.info!!.totalFrames)
        assertEquals(0L, state.frameIndex)
    }

    @Test
    fun steppingMovesOneFrameAtATimeAndStopsAtBothEnds() {
        onMain { state.seekToFrame(10L) }
        assertEquals(10L, state.frameIndex)

        onMain { repeat(5) { state.stepFrames(1L) } }
        assertEquals(15L, state.frameIndex)

        onMain { repeat(3) { state.stepFrames(-1L) } }
        assertEquals(12L, state.frameIndex)

        onMain { state.seekToFrame(0L); state.stepFrames(-1L) }
        assertEquals(0L, state.frameIndex)

        val last = state.info!!.lastFrameIndex
        onMain { state.seekToFrame(last); state.stepFrames(1L) }
        assertEquals(last, state.frameIndex)
    }

    @Test
    fun playingFromMidGopNeverJumpsBackwards() {
        val start = 45L
        onMain { state.seekToFrame(start) }
        assertEquals(start, state.frameIndex)

        runBlocking { state.applyPlaybackFps(30f) }
        onMain { state.play() }

        // Sample the position while it runs. With a sync-sample seek this drops straight back to
        // frame 30; with an exact seek it can only ever move forward.
        var lowest = Long.MAX_VALUE
        val deadline = SystemClock.uptimeMillis() + 4_000L
        while (SystemClock.uptimeMillis() < deadline && onMain { state.isPlaying }) {
            val observed = onMain { state.syncFrameFromPlayer(); state.frameIndex }
            lowest = minOf(lowest, observed)
            SystemClock.sleep(40L)
        }

        assertTrue(
            "playback started at frame $start but the position dropped to $lowest",
            lowest >= start,
        )
    }

    @Test
    fun pausingReportsTheFrameOnScreenAndRendersNothingFurther() {
        onMain { state.seekToFrame(20L) }
        runBlocking { state.applyPlaybackFps(30f) }
        onMain { state.play() }
        waitUntil("playback to move past frame 20") {
            state.syncFrameFromPlayer()
            state.frameIndex > 20L
        }

        onMain { state.pause() }
        // pause() returns before the player stops; give it room to come to rest.
        SystemClock.sleep(1_000L)

        val settled = onMain { state.frameIndex }
        val onScreen = onMain { state.renderedFrameIndex }
        assertEquals("the reported frame is not the frame on the surface", onScreen, settled)

        // The regression this guards: correcting the player onto the clock's idea of the position
        // re-rendered a *different* frame, so pausing flashed and stepped one frame forward. Once
        // paused, nothing may reach the surface at all.
        SystemClock.sleep(1_000L)
        assertEquals("a frame was rendered after the pause settled", onScreen, onMain { state.renderedFrameIndex })
        assertEquals("the position moved after the pause settled", settled, onMain { state.frameIndex })
    }

    @Test
    fun seekingToAFrameRendersThatExactFrame() {
        // The decoder test covers the extraction path; this is the same question for the path the
        // user actually looks at - an exact seek aimed at the middle of the frame's interval.
        for (index in listOf(0L, 1L, 29L, 30L, 31L, 45L, 59L)) {
            onMain { state.seekToFrame(index) }
            waitUntil("frame $index to reach the surface") { state.renderedFrameIndex == index }
            assertEquals(index, onMain { state.frameIndex })
        }
    }

    @Test
    fun unloadClearsTheClip() {
        onMain { state.unload() }
        assertEquals(null, state.info)
        assertEquals(0L, state.frameIndex)
    }
}
