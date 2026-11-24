package com.dns_technologies.mlkit_scanner.scanner.components.camera.x

import android.graphics.ImageFormat
import androidx.camera.core.ImageProxy
import com.dns_technologies.mlkit_scanner.scanner.components.camera.LumaGrid
import com.dns_technologies.mlkit_scanner.scanner.components.camera.LumaSamplingFrame
import com.dns_technologies.mlkit_scanner.scanner.components.camera.Rect
import com.dns_technologies.mlkit_scanner.scanner.utils.ImageProxyNv21Converter
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/** Camera frame that materializes full or cropped images as scoped NV21 buffers. */
internal class XCameraFrame(
    /** CameraX buffer owned by this frame until [close]. */
    private val imageProxy: ImageProxy,
    /** Shared converter that leases exclusive storage for one frame access. */
    private val nv21Converter: ImageProxyNv21Converter,
    previewWidth: Int,
    previewHeight: Int,
) : LumaSamplingFrame {
    override val rotationDegree: Int = imageProxy.imageInfo.rotationDegrees

    override val cropRect: Rect = calculatePreviewCrop(previewWidth, previewHeight)

    override val width: Int = imageProxy.width

    override val height: Int = imageProxy.height

    /** Whether the frame's single permitted NV21 access has begun. */
    private var isAccessed = false
    /** Whether the borrowed CameraX image has already been released. */
    private var isClosed = false

    @Synchronized
    override fun sampleLuma(cropRect: Rect?, destination: ByteArray): Boolean {
        if (isClosed || isAccessed) return false
        return try {
            if (imageProxy.format != ImageFormat.YUV_420_888) return false
            val planes: Array<ImageProxy.PlaneProxy>? = imageProxy.planes
            if (planes == null || planes.size < 3) return false
            val y = planes[0]
            val buffer: ByteBuffer? = y.buffer
            if (buffer == null) return false
            LumaGrid.samplePlane(buffer, width, height, y.rowStride, y.pixelStride, cropRect, destination)
        } catch (_: RuntimeException) {
            // Unusable luma metadata disables the early retry while keeping scheduled NV21 analysis available.
            false
        }
    }

    @Synchronized
    override fun <T> useNv21(cropRect: Rect?, block: (ByteArray, Int, Int, Int) -> T): T {
        check(!isClosed) { "Camera frame is already closed" }
        check(!isAccessed) { "Camera frame was already accessed" }
        isAccessed = true

        return nv21Converter.convert(imageProxy, cropRect) { bytes, outputWidth, outputHeight ->
            block(bytes, outputWidth, outputHeight, rotationDegree)
        }
    }

    @Synchronized
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
