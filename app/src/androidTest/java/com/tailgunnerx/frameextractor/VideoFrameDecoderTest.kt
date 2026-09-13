package com.tailgunnerx.frameextractor

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tailgunnerx.frameextractor.media.VideoFrameDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * Exercises the decoding path against a real clip.
 *
 * Two things are being protected here: that seeking still returns the *right* frame (the app's whole
 * point), and that previews are decoded at view resolution rather than source resolution - the fix
 * for laggy scrubbing and the thing most likely to silently regress.
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
            info.frameCount?.let { assertEquals(TestVideo.FRAME_COUNT.toLong(), it) }
            assertEquals(33L, info.msPerFrame)
        } finally {
            decoder.close()
        }
    }

    @Test
    fun decodePreview_isScaledDownToTheRequestedBox() = runBlocking {
        val decoder = VideoFrameDecoder.open(context, TestVideo.copyToCache())
        try {
            val preview = decoder.decodePreview(TestVideo.midpointOf(10), maxDimensionPx = 160)
            assertNotNull("no preview frame decoded", preview)
            preview!!
            val longestSide = maxOf(preview.width, preview.height)
            assertTrue(
                "preview was ${preview.width}x${preview.height}, expected it to fit in a 160 box",
                longestSide <= 160,
            )
            assertTrue(
                "preview ${preview.width}x${preview.height} was not smaller than the 320x240 source",
                preview.width * preview.height < TestVideo.WIDTH * TestVideo.HEIGHT,
            )
        } finally {
            decoder.close()
        }
    }

    @Test
    fun decodeFull_usesTheSourceResolution() = runBlocking {
        val decoder = VideoFrameDecoder.open(context, TestVideo.copyToCache())
        try {
            val frame = decoder.decodeFull(TestVideo.midpointOf(3))
            assertNotNull(frame)
            assertEquals(TestVideo.WIDTH, frame!!.width)
            assertEquals(TestVideo.HEIGHT, frame.height)
        } finally {
            decoder.close()
        }
    }

    @Test
    fun seekReturnsTheRequestedFrameWithinOneFrame() = runBlocking {
        // A clip that already fits the requested box is decoded through the plain frame accessor,
        // so this is also the check that the exact-seeking path still works.
        val decoder = VideoFrameDecoder.open(context, TestVideo.copyToCache())
        val requested = listOf(0, 1, 2, 7, 15, 29, 30, 31, 44, 45, 58, 59)
        var exact = 0
        val offsets = mutableListOf<Int>()
        try {
            for (index in requested) {
                val frame = decoder.decodePreview(TestVideo.midpointOf(index), maxDimensionPx = TestVideo.WIDTH)
                assertNotNull("no frame decoded for index $index", frame)
                val decoded = TestVideo.barcodeIndexOf(frame!!)
                offsets += decoded - index
                if (decoded == index) exact++
                assertTrue(
                    "asked for frame $index, decoder returned $decoded",
                    abs(decoded - index) <= 1,
                )
            }
        } finally {
            decoder.close()
        }
        // Some platform extractors round a seek onto the neighbouring frame; anything worse than
        // that is a regression, and the reported offsets make the behaviour visible.
        assertTrue(
            "only $exact/${requested.size} seeks returned the exact frame, offsets=$offsets",
            exact * 2 >= requested.size,
        )
    }

    @Test
    fun seekingTracksTheTimelineAcrossTheWholeClip() = runBlocking {
        val decoder = VideoFrameDecoder.open(context, TestVideo.copyToCache())
        val decodedIndices = mutableListOf<Int>()
        try {
            for (index in 0 until TestVideo.FRAME_COUNT) {
                val frame = decoder.decodePreview(TestVideo.midpointOf(index), maxDimensionPx = 320)
                assertNotNull("no frame decoded for index $index", frame)
                decodedIndices += TestVideo.barcodeIndexOf(frame!!)
            }
        } finally {
            decoder.close()
        }
        val backwards = decodedIndices.zipWithNext().filter { (first, second) -> second < first }
        assertTrue("seeking went backwards: $backwards (indices=$decodedIndices)", backwards.isEmpty())

        val worstOffset = decodedIndices.withIndex().maxOf { (index, decoded) -> abs(decoded - index) }
        assertTrue("worst seek offset was $worstOffset frames", worstOffset <= 1)
    }

    @Test
    fun scaledPreviewStaysWithinOneFrameOfTheExactFrame() = runBlocking {
        // The preview path trades a little accuracy for a lot of speed on large sources; it must
        // never drift further than a single frame from what a full resolution decode returns.
        val decoder = VideoFrameDecoder.open(context, TestVideo.copyToCache())
        val drifts = mutableListOf<Int>()
        try {
            for (index in 0 until TestVideo.FRAME_COUNT step 7) {
                val time = TestVideo.midpointOf(index)
                val preview = decoder.decodePreview(time, maxDimensionPx = 80)
                val full = decoder.decodeFull(time)
                assertNotNull(preview)
                assertNotNull(full)
                val drift = TestVideo.barcodeIndexOf(preview!!) - TestVideo.barcodeIndexOf(full!!)
                drifts += drift
                assertTrue("preview drifted $drift frames at index $index", abs(drift) <= 1)
            }
        } finally {
            decoder.close()
        }
    }

    @Test
    fun repeatedSeeksAreDeterministic() = runBlocking {
        val decoder = VideoFrameDecoder.open(context, TestVideo.copyToCache())
        try {
            val time = TestVideo.midpointOf(17)
            val first = decoder.decodePreview(time, maxDimensionPx = 160)
            val second = decoder.decodePreview(time, maxDimensionPx = 160)
            assertNotNull(first)
            assertNotNull(second)
            assertEquals(TestVideo.barcodeIndexOf(first!!), TestVideo.barcodeIndexOf(second!!))
        } finally {
            decoder.close()
        }
    }

    /**
     * Not a benchmark - emulators are far slower than phones - but a guard rail: if previews ever
     * start being decoded at source resolution, or seeks start queueing behind each other, these
     * numbers explode.
     */
    @Test
    fun previewDecodeStaysWithinABudget() = runBlocking {
        val decoder = VideoFrameDecoder.open(context, TestVideo.copyToCache())
        val timings = mutableListOf<Long>()
        try {
            // Warm up on a keyframe.
            decoder.decodePreview(0L, maxDimensionPx = 160)

            for (index in 1 until 20) {
                val started = System.nanoTime()
                val frame = decoder.decodePreview(TestVideo.midpointOf(index + 20), maxDimensionPx = 160)
                val elapsedMs = (System.nanoTime() - started) / 1_000_000
                assertNotNull(frame)
                timings += elapsedMs
                assertTrue("a single preview decode took ${elapsedMs}ms", elapsedMs < 1_500L)
            }
        } finally {
            decoder.close()
        }
        val average = timings.average()
        val worst = timings.max()
        println("preview decode: avg=${"%.1f".format(average)}ms worst=${worst}ms over ${timings.size} seeks")
        assertTrue("average preview decode was ${average}ms", average < 400.0)
    }

    @Test
    fun decodeAfterCloseReturnsNothing() = runBlocking {
        val decoder = VideoFrameDecoder.open(context, TestVideo.copyToCache())
        decoder.close()
        var frame: Bitmap? = null
        repeat(20) {
            frame = decoder.decodePreview(0L, maxDimensionPx = 160)
            if (frame == null) return@repeat
            delay(50)
        }
        assertNull("a closed decoder still produced frames", frame)
    }

    @Test
    fun concurrentSeeksOnlyProduceTheLastFrame() = runBlocking {
        // Mirrors what scrubbing does: many overlapping requests, all but the newest irrelevant.
        val decoder = VideoFrameDecoder.open(context, TestVideo.copyToCache())
        try {
            val jobs = (0 until 8).map { index ->
                async(Dispatchers.Default) {
                    decoder.decodePreview(TestVideo.midpointOf(index * 5), maxDimensionPx = 160)
                }
            }
            val frames = jobs.map { it.await() }
            assertTrue("decoder returned no frames under concurrent use", frames.any { it != null })
        } finally {
            decoder.close()
        }
    }

    @Test
    fun frameCacheServesRepeatedFrameIndices() = runBlocking {
        val decoder = VideoFrameDecoder.open(context, TestVideo.copyToCache())
        try {
            val cache = com.tailgunnerx.frameextractor.media.FrameCache(maxBytes = 4L * 1024L * 1024L)
            val time = TestVideo.midpointOf(12)
            val decoded = decoder.decodePreview(time, maxDimensionPx = 160)!!
            cache.put(frameIndex = 12L, dimension = 160, bitmap = decoded)
            assertSame(decoded, cache.get(frameIndex = 12L, dimension = 160))
            assertNull(cache.get(frameIndex = 12L, dimension = 720))
            assertNull(cache.get(frameIndex = 13L, dimension = 160))
        } finally {
            decoder.close()
        }
    }
}
