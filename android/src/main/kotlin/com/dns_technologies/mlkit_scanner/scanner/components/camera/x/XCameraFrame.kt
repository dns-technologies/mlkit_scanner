package com.dns_technologies.mlkit_scanner.scanner.components.camera.x

import android.media.Image
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy
import com.dns_technologies.mlkit_scanner.scanner.components.camera.CameraFrame
import com.dns_technologies.mlkit_scanner.scanner.components.camera.Rect
import com.dns_technologies.mlkit_scanner.scanner.utils.ByteArrayLease
import com.dns_technologies.mlkit_scanner.scanner.utils.ImageProxyNv21Converter
import com.google.mlkit.vision.common.InputImage

/** CameraX-backed frame that materializes full images zero-copy and cropped images as NV21. */
internal class XCameraFrame(
    private val imageProxy: ImageProxy,
    private val nv21Converter: ImageProxyNv21Converter,
    private val fromMediaImage: (Image, Int) -> InputImage = InputImage::fromMediaImage,
    private val fromByteArray: (ByteArray, Int, Int, Int, Int) -> InputImage =
        InputImage::fromByteArray,
) : CameraFrame {
    override val width: Int = imageProxy.width

    override val height: Int = imageProxy.height

    override val rotationDegree: Int = imageProxy.imageInfo.rotationDegrees

    private var bufferLease: ByteArrayLease? = null
    private var isMaterialized = false
    private var isClosed = false

    @ExperimentalGetImage
    @Synchronized
    override fun toInputImage(cropRect: Rect?): InputImage {
        check(!isClosed) { "Camera frame is already closed" }
        check(!isMaterialized) { "Camera frame is already materialized" }
        isMaterialized = true

        if (cropRect.isFullFrame()) {
            imageProxy.image?.let { mediaImage ->
                return fromMediaImage(mediaImage, rotationDegree)
            }
        }

        val crop = nv21Converter.normalizeCropRect(cropRect, width, height)
        val lease = nv21Converter.convert(imageProxy, crop)
        return try {
            fromByteArray(
                lease.data,
                crop.width,
                crop.height,
                rotationDegree,
                InputImage.IMAGE_FORMAT_NV21,
            ).also { bufferLease = lease }
        } catch (error: Throwable) {
            lease.close()
            throw error
        }
    }

    @Synchronized
    override fun close() {
        if (isClosed) return
        isClosed = true
        bufferLease?.close()
        bufferLease = null
        imageProxy.close()
    }

    private fun Rect?.isFullFrame(): Boolean =
        this == null || (
            left == 0 &&
                top == 0 &&
                right == this@XCameraFrame.width &&
                bottom == this@XCameraFrame.height
        )
}
