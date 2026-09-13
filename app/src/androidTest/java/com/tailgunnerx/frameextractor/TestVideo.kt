package com.tailgunnerx.frameextractor

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/**
 * The clip used by the instrumented tests: 320x240, 30 fps, 60 frames, and an I-frame only every
 * 30 frames so that most seeks have to decode forward rather than land on a keyframe.
 *
 * Every frame carries its own frame index as a 6-bit black/white barcode, which lets a test assert
 * *which* frame a seek actually returned.
 */
object TestVideo {

    const val ASSET_NAME = "frame_barcode_320x240_30fps_2s.mp4"
    const val WIDTH = 320
    const val HEIGHT = 240
    const val FPS = 30
    const val FRAME_COUNT = 60
    const val DURATION_MS = 2000L
    const val KEYFRAME_INTERVAL = 30

    private const val BITS = 6
    private const val BAR_WIDTH = 48
    private const val BAR_LEFT = 16

    /** Copies the asset out of the test APK and returns a `file://` uri the app can open. */
    fun copyToCache(): Uri {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = File(instrumentation.targetContext.cacheDir, ASSET_NAME)
        if (!target.exists()) {
            instrumentation.context.assets.open(ASSET_NAME).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return Uri.fromFile(target)
    }

    /** Timestamp in the middle of [index]; the half-frame offset makes the nearest frame unambiguous. */
    fun midpointOf(index: Int): Long = ((index + 0.5) * 1000.0 / FPS).toLong()

    /** Reads the barcode back out of a decoded frame, at any scale. */
    fun barcodeIndexOf(bitmap: Bitmap): Int {
        var index = 0
        val y = (bitmap.height / 2).coerceIn(0, bitmap.height - 1)
        for (bit in 0 until BITS) {
            val sourceX = BAR_LEFT + bit * BAR_WIDTH + BAR_WIDTH / 2f
            val x = (bitmap.width * sourceX / WIDTH).toInt().coerceIn(0, bitmap.width - 1)
            val pixel = bitmap.getPixel(x, y)
            val luma = (Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)) / 3
            if (luma > 128) index = index or (1 shl bit)
        }
        return index
    }
}
