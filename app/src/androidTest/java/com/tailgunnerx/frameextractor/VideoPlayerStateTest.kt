package com.tailgunnerx.frameextractor

import android.os.SystemClock
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tailgunnerx.frameextractor.ui.VideoPlayerState
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
        state = onMain { VideoPlayerState(ExoPlayer.Builder(context.applicationContext).build()) }
        val uri = TestVideo.copyToCache()
        val error = runBlocking(Dispatchers.Main) { state.load(context, uri) }
        assertEquals(null, error)
        waitUntil("player to become ready") { state.player.playbackState == ExoPlayer.STATE_READY }
        assertEquals(null, state.playbackError)
    }

    @After
    fun tearDown() {
        onMain { state.release() }
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

        onMain { state.setPlaybackFps(30f) }
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
    fun pausingKeepsTheFrameItStoppedOn() {
        onMain { state.seekToFrame(20L) }
        onMain { state.setPlaybackFps(30f) }
        onMain { state.play() }
        waitUntil("playback to move past frame 20") {
            state.syncFrameFromPlayer()
            state.frameIndex > 20L
        }

        val atPause = onMain { state.pause(); state.frameIndex }
        SystemClock.sleep(500L)
        // Nothing may move the position after a pause - no background re-seek, no late poll.
        assertEquals(atPause, onMain { state.frameIndex })

        // And the frame it reports has to be the frame the player is actually sitting on.
        val fromPlayer = onMain {
            com.tailgunnerx.frameextractor.util.Timeline.frameIndexAt(
                state.player.currentPosition.coerceAtLeast(0L),
                state.fps,
            )
        }
        assertEquals(atPause, fromPlayer)
    }

    @Test
    fun unloadClearsTheClip() {
        onMain { state.unload() }
        assertEquals(null, state.info)
        assertEquals(0L, state.frameIndex)
    }
}
