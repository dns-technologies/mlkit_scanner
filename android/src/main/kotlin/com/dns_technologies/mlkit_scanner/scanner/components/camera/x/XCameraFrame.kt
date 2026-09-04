package com.dns_technologies.mlkit_scanner.scanner.components.camera.x

import androidx.camera.core.ImageProxy
import com.dns_technologies.mlkit_scanner.scanner.components.camera.CameraFrame
import com.dns_technologies.mlkit_scanner.scanner.components.camera.Rect
import com.dns_technologies.mlkit_scanner.scanner.utils.ImageProxyNv21Converter

/** Camera frame that materializes full or cropped images as scoped NV21 buffers. */
internal class XCameraFrame(
    private val imageProxy: ImageProxy,
    private val nv21Converter: ImageProxyNv21Converter,
    previewWidth: Int,
    previewHeight: Int,
    previewCropState: PreviewCropState = PreviewCropState(),
) : CameraFrame {
    override val rotationDegree: Int = imageProxy.imageInfo.rotationDegrees

    override val cropRect: Rect = imageProxy.cropRect.let { cropRect ->
        previewCropState.resolve(
            source = Rect(cropRect.left, cropRect.top, cropRect.right, cropRect.bottom),
            rotationDegrees = rotationDegree,
            previewWidth = previewWidth,
            previewHeight = previewHeight,
        )
    }

    override val width: Int = imageProxy.width

    override val height: Int = imageProxy.height

    private var isMaterialized = false
    private var isClosed = false

    @Synchronized
    override fun <T> useNv21(
        cropRect: Rect?,
        block: (ByteArray, Int, Int, Int) -> T,
    ): T {
        check(!isClosed) { "Camera frame is already closed" }
        check(!isMaterialized) { "Camera frame is already materialized" }
        isMaterialized = true

        return nv21Converter.convert(imageProxy, cropRect) { bytes, outputWidth, outputHeight ->
            block(
                bytes,
                outputWidth,
                outputHeight,
                rotationDegree,
            )
        }
    }

    @Synchronized
    override fun close() {
        if (isClosed) return
        isClosed = true
        imageProxy.close()
    }
}
