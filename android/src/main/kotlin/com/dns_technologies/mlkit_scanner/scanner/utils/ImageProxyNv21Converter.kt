package com.dns_technologies.mlkit_scanner.scanner.utils

import android.graphics.ImageFormat
import android.util.Size
import androidx.camera.core.ImageProxy
import com.dns_technologies.mlkit_scanner.scanner.models.images.NV21AnalysingImage

/** Converts CameraX image frames into scanner images expected by ML Kit. */
internal object ImageProxyNv21Converter {
    /** Converts the CameraX YUV image into the NV21 format expected by the analyzer. */
    fun convert(image: ImageProxy): NV21AnalysingImage =
        NV21AnalysingImage(
            image.toNv21ByteArray(),
            Size(image.width, image.height),
            ImageFormat.NV21,
            image.imageInfo.rotationDegrees,
        )

    private fun ImageProxy.toNv21ByteArray(): ByteArray {
        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]

        val ySize = width * height
        val uvSize = width * height / 2
        val nv21 = ByteArray(ySize + uvSize)

        val outputOffset = copyYPlane(yPlane, nv21)
        copyUvPlanes(vPlane, uPlane, nv21, outputOffset)

        return nv21
    }

    private fun ImageProxy.copyYPlane(
        yPlane: ImageProxy.PlaneProxy,
        output: ByteArray,
    ): Int {
        var outputOffset = 0
        for (row in 0 until height) {
            val inputOffset = row * yPlane.rowStride
            for (col in 0 until width) {
                output[outputOffset++] = yPlane.buffer.get(inputOffset + col * yPlane.pixelStride)
            }
        }
        return outputOffset
    }

    private fun ImageProxy.copyUvPlanes(
        vPlane: ImageProxy.PlaneProxy,
        uPlane: ImageProxy.PlaneProxy,
        output: ByteArray,
        initialOutputOffset: Int,
    ) {
        var outputOffset = initialOutputOffset
        val chromaHeight = height / 2
        val chromaWidth = width / 2
        for (row in 0 until chromaHeight) {
            val uRowOffset = row * uPlane.rowStride
            val vRowOffset = row * vPlane.rowStride
            for (col in 0 until chromaWidth) {
                output[outputOffset++] = vPlane.buffer.get(vRowOffset + col * vPlane.pixelStride)
                output[outputOffset++] = uPlane.buffer.get(uRowOffset + col * uPlane.pixelStride)
            }
        }
    }
}
