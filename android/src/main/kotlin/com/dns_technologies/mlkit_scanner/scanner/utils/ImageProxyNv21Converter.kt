package com.dns_technologies.mlkit_scanner.scanner.utils

import androidx.camera.core.ImageProxy
import com.dns_technologies.mlkit_scanner.scanner.components.camera.Rect

/** Copies only the requested camera image region into reusable NV21 storage. */
internal class ImageProxyNv21Converter(
    private val bufferPool: ReusableByteArrayPool = ReusableByteArrayPool(),
) {
    /** Converts a full or cropped frame and scopes access to the pooled NV21 buffer. */
    fun <T> convert(
        image: ImageProxy,
        cropRect: Rect?,
        block: (bytes: ByteArray, width: Int, height: Int) -> T,
    ): T {
        val crop = normalizeCropRect(cropRect, image.width, image.height)
        val planes = image.planes
        require(planes.size >= PLANE_COUNT) { "Camera image must contain three planes" }
        val yPlaneSize = crop.width * crop.height
        return bufferPool.acquire(yPlaneSize + yPlaneSize / 2).use { bufferLease ->
            val output = bufferLease.data
            copyYPlane(planes[Y_PLANE_INDEX], crop, output)
            copyUvPlanes(
                uPlane = planes[U_PLANE_INDEX],
                vPlane = planes[V_PLANE_INDEX],
                crop = crop,
                output = output,
                outputOffset = yPlaneSize,
            )
            block(output, crop.width, crop.height)
        }
    }

    /** Releases retained conversion buffers. Active leases remain independently valid. */
    fun dispose() {
        bufferPool.dispose()
    }

    /** Clamps, orders, and chroma-aligns a requested crop to the camera buffer. */
    private fun normalizeCropRect(
        cropRect: Rect?,
        width: Int,
        height: Int,
    ): Rect {
        val imageWidth = width.roundDownToEven()
        val imageHeight = height.roundDownToEven()
        require(imageWidth >= MIN_CROP_SIZE && imageHeight >= MIN_CROP_SIZE)
        val requested = cropRect ?: Rect(0, 0, imageWidth, imageHeight)
        val left = minOf(requested.left, requested.right).roundUpToEven().coerceIn(0, imageWidth)
        val top = minOf(requested.top, requested.bottom).roundUpToEven().coerceIn(0, imageHeight)
        val right = maxOf(requested.left, requested.right).roundDownToEven().coerceIn(0, imageWidth)
        val bottom = maxOf(requested.top, requested.bottom).roundDownToEven().coerceIn(0, imageHeight)
        require(right - left >= MIN_CROP_SIZE && bottom - top >= MIN_CROP_SIZE) {
            "Camera crop must contain at least one YUV chroma sample"
        }

        return Rect(
            left,
            top,
            right,
            bottom,
        )
    }

    /** Copies the cropped luminance plane into the beginning of [output]. */
    private fun copyYPlane(
        plane: ImageProxy.PlaneProxy,
        crop: Rect,
        output: ByteArray,
    ) {
        val buffer = plane.buffer.duplicate()
        val baseOffset = buffer.position()
        var outputOffset = 0

        for (row in crop.top until crop.bottom) {
            val rowOffset = baseOffset + row * plane.rowStride + crop.left * plane.pixelStride
            if (plane.pixelStride == 1) {
                buffer.position(rowOffset)
                buffer.get(output, outputOffset, crop.width)
                outputOffset += crop.width
            } else {
                for (column in 0 until crop.width) {
                    output[outputOffset++] = buffer.get(rowOffset + column * plane.pixelStride)
                }
            }
        }
    }

    /** Interleaves the cropped V and U chroma planes after the luminance bytes. */
    private fun copyUvPlanes(
        uPlane: ImageProxy.PlaneProxy,
        vPlane: ImageProxy.PlaneProxy,
        crop: Rect,
        output: ByteArray,
        outputOffset: Int,
    ) {
        val uBuffer = uPlane.buffer.duplicate()
        val vBuffer = vPlane.buffer.duplicate()
        val uBaseOffset = uBuffer.position()
        val vBaseOffset = vBuffer.position()
        val chromaLeft = crop.left / 2
        val chromaTop = crop.top / 2
        val chromaWidth = crop.width / 2
        val chromaHeight = crop.height / 2
        var destinationOffset = outputOffset

        for (row in 0 until chromaHeight) {
            val uRowOffset = uBaseOffset + (chromaTop + row) * uPlane.rowStride
            val vRowOffset = vBaseOffset + (chromaTop + row) * vPlane.rowStride
            for (column in 0 until chromaWidth) {
                output[destinationOffset++] = vBuffer.get(
                    vRowOffset + (chromaLeft + column) * vPlane.pixelStride,
                )
                output[destinationOffset++] = uBuffer.get(
                    uRowOffset + (chromaLeft + column) * uPlane.pixelStride,
                )
            }
        }
    }

    /** Rounds this coordinate down to the nearest YUV chroma boundary. */
    private fun Int.roundDownToEven(): Int = this and -2

    /** Rounds this coordinate up to the nearest YUV chroma boundary. */
    private fun Int.roundUpToEven(): Int = (this + 1) and -2

    private companion object {
        const val PLANE_COUNT = 3
        const val Y_PLANE_INDEX = 0
        const val U_PLANE_INDEX = 1
        const val V_PLANE_INDEX = 2
        const val MIN_CROP_SIZE = 2
    }
}
