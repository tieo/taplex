package de.tieo.wordtap

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection

/**
 * Keeps one VirtualDisplay alive for the whole session and pulls single frames from it
 * on demand. Re-creating the display per tap would cost several hundred milliseconds.
 */
class ScreenCapturer(
    private val projection: MediaProjection,
    private val width: Int,
    private val height: Int,
    private val densityDpi: Int
) {
    private val imageReader: ImageReader =
        ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

    private var virtualDisplay: VirtualDisplay? = null

    fun start() {
        virtualDisplay = projection.createVirtualDisplay(
            "wordtap",
            width,
            height,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface,
            null,
            null
        )
    }

    /** Returns the most recent frame, or null if the reader has not produced one yet. */
    fun grab(): Bitmap? {
        val image: Image = imageReader.acquireLatestImage() ?: return null
        return try {
            image.toBitmap()
        } finally {
            image.close()
        }
    }

    private fun Image.toBitmap(): Bitmap {
        val plane = planes[0]
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        // The buffer rows are padded to the stride, so the bitmap is created wider and cropped.
        val rowPadding = rowStride - pixelStride * width
        val paddedWidth = width + rowPadding / pixelStride
        val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(plane.buffer)
        return if (paddedWidth == width) {
            padded
        } else {
            Bitmap.createBitmap(padded, 0, 0, width, height).also { padded.recycle() }
        }
    }

    fun release() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader.close()
    }
}
