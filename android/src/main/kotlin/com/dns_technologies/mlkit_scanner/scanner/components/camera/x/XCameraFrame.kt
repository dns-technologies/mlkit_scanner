package com.dns_technologies.mlkit_scanner.scanner.components.camera.x

import androidx.camera.core.ImageProxy
import com.dns_technologies.mlkit_scanner.scanner.components.camera.CameraFrame
import com.dns_technologies.mlkit_scanner.scanner.components.camera.Rect
import com.dns_technologies.mlkit_scanner.scanner.utils.ImageProxyNv21Converter
import kotlin.math.roundToInt

/** Camera frame that materializes full or cropped images as scoped NV21 buffers. */
internal class XCameraFrame(
    /** CameraX buffer owned by this frame until [close]. */
    private val imageProxy: ImageProxy,
    /** Shared converter that leases exclusive storage for one frame access. */
    private val nv21Converter: ImageProxyNv21Converter,
    previewWidth: Int,
    previewHeight: Int,
) : CameraFrame {
    /** Clockwise rotation required to display this source buffer upright. */
    override val rotationDegree: Int = imageProxy.imageInfo.rotationDegrees

    /** Snapshot of source pixels visible in the selected widget's fill-center preview. */
    override val cropRect: Rect = calculatePreviewCrop(previewWidth, previewHeight)

    /** Unrotated source-buffer width in pixels. */
    override val width: Int = imageProxy.width

    /** Unrotated source-buffer height in pixels. */
    override val height: Int = imageProxy.height

    /** Whether the frame's single permitted NV21 access has begun. */
    private var isAccessed = false
    /** Whether the borrowed CameraX image has already been released. */
    private var isClosed = false

    // Keep close() from another thread from invalidating the image during conversion/use.
    @Synchronized
    /** Converts and borrows NV21 bytes while holding off concurrent frame closure. */
    override fun <T> useNv21(cropRect: Rect?, block: (ByteArray, Int, Int, Int) -> T): T {
        check(!isClosed) { "Camera frame is already closed" }
        check(!isAccessed) { "Camera frame was already accessed" }
        isAccessed = true

        return nv21Converter.convert(imageProxy, cropRect) { bytes, outputWidth, outputHeight ->
            block(bytes, outputWidth, outputHeight, rotationDegree)
        }
    }

    @Synchronized
    /** Releases the CameraX image once, after any active buffer access finishes. */
    override fun close() {
        if (isClosed) return
        isClosed = true
        imageProxy.close()
    }

    /** Snapshots the centered source region visible in the current fill-center preview. */
    private fun calculatePreviewCrop(previewWidth: Int, previewHeight: Int): Rect {
        val crop = imageProxy.cropRect
        val source = Rect(crop.left, crop.top, crop.right, crop.bottom)
        if (source.isEmpty || previewWidth <= 0 || previewHeight <= 0) return source

        val rotated = rotationDegree == 90 || rotationDegree == 270
        // Express the preview aspect in source axes; offsets stay in unrotated image pixels.
        val aspect =
            if (rotated) previewHeight.toDouble() / previewWidth
            else previewWidth.toDouble() / previewHeight
        val horizontalInsetPixels = centeredInset(source.width, source.height * aspect)
        val verticalInsetPixels = centeredInset(source.height, source.width / aspect)
        return Rect(
            left = source.left + horizontalInsetPixels,
            top = source.top + verticalInsetPixels,
            right = source.right - horizontalInsetPixels,
            bottom = source.bottom - verticalInsetPixels,
        )
    }

    // Symmetric integer insets preserve the center. Keep at least one source pixel even when
    // the ideal visible strip is subpixel-sized (two pixels for an even-sized source axis).
    /** Returns a symmetric inset that preserves at least one source pixel. */
    private fun centeredInset(sourceSize: Int, visibleSize: Double): Int =
        ((sourceSize - visibleSize) / 2).roundToInt().coerceIn(0, (sourceSize - 1) / 2)
}
