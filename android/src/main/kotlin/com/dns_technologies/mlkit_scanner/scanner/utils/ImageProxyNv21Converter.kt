package com.dns_technologies.mlkit_scanner.scanner.utils

import androidx.camera.core.ImageProxy
import com.dns_technologies.mlkit_scanner.scanner.components.camera.Rect

/** Copies only the requested CameraX YUV region into reusable NV21 storage. */
internal class ImageProxyNv21Converter(
    private val bufferPool: ReusableByteArrayPool = ReusableByteArrayPool(),
) {
    /** Converts a bounded, even crop region without materializing the full frame. */
    fun convert(image: ImageProxy, cropRect: Rect?): ByteArrayLease {
        val planes = image.planes
        require(planes.size >= PLANE_COUNT) { "YUV image must contain three planes" }
        val crop = normalizeCropRect(cropRect, image.width, image.height)
        val yPlaneSize = crop.width * crop.height
        val bufferLease = bufferPool.acquire(yPlaneSize + yPlaneSize / 2)

        return try {
            copyYPlane(planes[Y_PLANE_INDEX], crop, bufferLease.data)
            copyUvPlanes(
                uPlane = planes[U_PLANE_INDEX],
                vPlane = planes[V_PLANE_INDEX],
                crop = crop,
                output = bufferLease.data,
                outputOffset = yPlaneSize,
            )
            bufferLease
        } catch (error: Throwable) {
            bufferLease.close()
            throw error
        }
    }

    /** Releases retained conversion buffers. Active leases remain independently valid. */
    fun dispose() {
        bufferPool.dispose()
    }

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

    fun normalizeCropRect(
        cropRect: Rect?,
        width: Int,
        height: Int,
    ): Rect {
        val imageWidth = width.roundDownToEven()
        val imageHeight = height.roundDownToEven()
        require(imageWidth >= MIN_CROP_SIZE && imageHeight >= MIN_CROP_SIZE)
        val requested = cropRect ?: Rect(0, 0, imageWidth, imageHeight)
        val left = minOf(requested.left, requested.right).roundDownToEven().coerceIn(0, imageWidth)
        val top = minOf(requested.top, requested.bottom).roundDownToEven().coerceIn(0, imageHeight)
        val right = maxOf(requested.left, requested.right).roundDownToEven().coerceIn(0, imageWidth)
        val bottom = maxOf(requested.top, requested.bottom).roundDownToEven().coerceIn(0, imageHeight)
        val cropLeft = left.coerceAtMost(imageWidth - MIN_CROP_SIZE)
        val cropTop = top.coerceAtMost(imageHeight - MIN_CROP_SIZE)

        return Rect(
            cropLeft,
            cropTop,
            right.coerceAtLeast(cropLeft + MIN_CROP_SIZE).coerceAtMost(imageWidth),
            bottom.coerceAtLeast(cropTop + MIN_CROP_SIZE).coerceAtMost(imageHeight),
        )
    }

    private fun Int.roundDownToEven(): Int = this - (this % 2)

    private companion object {
        const val PLANE_COUNT = 3
        const val Y_PLANE_INDEX = 0
        const val U_PLANE_INDEX = 1
        const val V_PLANE_INDEX = 2
        const val MIN_CROP_SIZE = 2
    }
}
