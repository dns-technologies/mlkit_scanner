package com.dns_technologies.mlkit_scanner.scanner.models.images

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Size
import java.io.ByteArrayOutputStream

/**
 * [AnalysingImage] implementation representing an image of the [ImageFormat.NV21] format.
 *
 * @property format Android image format. Expected to be [ImageFormat.NV21].
 * @property rotationDegree Rotation, in degrees, required to match device orientation.
 */
class NV21AnalysingImage(
    data: ByteArray,
    size: Size,
    override val format: Int,
    override val rotationDegree: Int,
) : AnalysingImage {
    /** Raw NV21 image bytes. Updated when [crop] is called. */
    override var data: ByteArray = data
        private set

    /** Current image size. Updated when [crop] is called. */
    override var size: Size = size
        private set

    /** Converts the current NV21 image data to a bitmap for diagnostics. */
    override fun toBitmap(): Bitmap? = bitmapFromBytes()

    /** Crops the current NV21 image to the requested pixel rectangle. */
    override fun crop(left: Int, top: Int, right: Int, bottom: Int) {
        val cropRect = normalizeCropBounds(left, top, right, bottom)
        if (cropRect.isEmpty || cropRect.isFullImage()) return

        data = cropNv21(cropRect)
        size = Size(cropRect.width(), cropRect.height())
    }

    /** Normalizes requested crop bounds to valid even NV21 pixel coordinates. */
    private fun normalizeCropBounds(left: Int, top: Int, right: Int, bottom: Int): Rect {
        val imageWidth = width.roundDownToEven()
        val imageHeight = height.roundDownToEven()
        if (imageWidth < MIN_CROP_SIZE || imageHeight < MIN_CROP_SIZE) return Rect()

        val normalizedLeft = minOf(left, right).roundDownToEven().coerceIn(0, imageWidth)
        val normalizedTop = minOf(top, bottom).roundDownToEven().coerceIn(0, imageHeight)
        val normalizedRight = maxOf(left, right).roundDownToEven().coerceIn(0, imageWidth)
        val normalizedBottom = maxOf(top, bottom).roundDownToEven().coerceIn(0, imageHeight)

        return Rect(
            normalizedLeft,
            normalizedTop,
            normalizedRight,
            normalizedBottom,
        ).ensureMinimumSize(imageWidth, imageHeight)
    }

    /** Expands this rectangle to the minimum crop size within image bounds. */
    private fun Rect.ensureMinimumSize(imageWidth: Int, imageHeight: Int): Rect {
        val cropLeft = left.coerceAtMost(imageWidth - MIN_CROP_SIZE)
        val cropTop = top.coerceAtMost(imageHeight - MIN_CROP_SIZE)
        val cropRight = right.coerceAtLeast(cropLeft + MIN_CROP_SIZE).coerceAtMost(imageWidth)
        val cropBottom = bottom.coerceAtLeast(cropTop + MIN_CROP_SIZE).coerceAtMost(imageHeight)
        return Rect(cropLeft, cropTop, cropRight, cropBottom)
    }

    /** Creates a cropped NV21 byte array for the provided image-space rectangle. */
    private fun cropNv21(cropRect: Rect): ByteArray {
        val cropWidth = cropRect.width()
        val cropHeight = cropRect.height()
        val yPlaneSize = cropWidth * cropHeight
        val output = ByteArray(yPlaneSize + yPlaneSize / 2)

        copyYPlane(cropRect, output)
        copyUvPlane(cropRect, output)

        return output
    }

    /** Copies the luminance plane for the requested crop rectangle. */
    private fun copyYPlane(cropRect: Rect, output: ByteArray) {
        val cropWidth = cropRect.width()
        repeat(cropRect.height()) { row ->
            System.arraycopy(
                data,
                (cropRect.top + row) * width + cropRect.left,
                output,
                row * cropWidth,
                cropWidth,
            )
        }
    }

    /** Copies the interleaved chroma plane for the requested crop rectangle. */
    private fun copyUvPlane(cropRect: Rect, output: ByteArray) {
        val cropWidth = cropRect.width()
        val cropHeight = cropRect.height()
        val outputUvOffset = cropWidth * cropHeight
        val sourceUvOffset = width * height
        val sourceUvTop = cropRect.top / 2

        repeat(cropHeight / 2) { row ->
            System.arraycopy(
                data,
                sourceUvOffset + (sourceUvTop + row) * width + cropRect.left,
                output,
                outputUvOffset + row * cropWidth,
                cropWidth,
            )
        }
    }

    /** Returns true when this rectangle covers the whole current image. */
    private fun Rect.isFullImage(): Boolean =
        left == 0 && top == 0 && right == width && bottom == height

    /** Rounds this value down to an even coordinate required by NV21 chroma sampling. */
    private fun Int.roundDownToEven(): Int = this - (this % 2)

    /** Converts the current NV21 byte array into a [Bitmap] for debugging and image verification. */
    private fun bitmapFromBytes(): Bitmap? =
        try {
            ByteArrayOutputStream().use { stream ->
                YuvImage(data, ImageFormat.NV21, width, height, null)
                    .compressToJpeg(Rect(0, 0, width, height), JPEG_QUALITY, stream)
                BitmapFactory.decodeByteArray(stream.toByteArray(), 0, stream.size())
            }
        } catch (exception: Exception) {
            exception.printStackTrace()
            null
        }

    private companion object {
        const val JPEG_QUALITY = 80
        const val MIN_CROP_SIZE = 2
    }
}
