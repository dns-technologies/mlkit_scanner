package com.dns_technologies.mlkit_scanner.scanner.components.camera

import java.nio.ByteBuffer

/** Samples a chroma-aligned crop without copying the luma plane. */
internal object LumaGrid {
    private const val GRID_AXIS = 8
    private const val MIN_ROI_AXIS = 64
    const val SAMPLE_COUNT = 2 * GRID_AXIS * GRID_AXIS

    /** Reads within the buffer's position and limit without advancing it. */
    fun samplePlane(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
        crop: Rect?,
        destination: ByteArray,
    ): Boolean {
        val baseOffset = buffer.position()
        val limit = buffer.limit()
        if (destination.size != SAMPLE_COUNT || rowStride <= 0 || pixelStride <= 0) return false
        val maxWidth = width and -2
        val maxHeight = height and -2
        val left = crop?.left ?: 0
        val top = crop?.top ?: 0
        val right = crop?.right ?: maxWidth
        val bottom = crop?.bottom ?: maxHeight
        if (left < 0 || top < 0 || right > maxWidth || bottom > maxHeight ||
            right <= left || bottom <= top || ((left or top or right or bottom) and 1) != 0
        ) return false
        val cropWidth = right - left
        val cropHeight = bottom - top
        // The overlapping grids need enough pixels to avoid sampling the same locations.
        if (cropWidth < MIN_ROI_AXIS || cropHeight < MIN_ROI_AXIS) return false
        val lastColumn = (right - 1L) * pixelStride
        val last = baseOffset + (bottom - 1L) * rowStride + lastColumn
        if (lastColumn >= rowStride || last >= limit) return false

        var sampleIndex = 0
        for (grid in 0..1) {
            // The denser grid samples the central quarter of each axis.
            val denominator = if (grid == 0) 16 else 64
            val firstNumerator = if (grid == 0) 1 else 25
            for (row in 0 until GRID_AXIS) {
                val y = top + (cropHeight.toLong() * (firstNumerator + 2 * row) / denominator).toInt()
                val rowStart = baseOffset + y * rowStride
                for (column in 0 until GRID_AXIS) {
                    val x = left + (cropWidth.toLong() * (firstNumerator + 2 * column) / denominator).toInt()
                    destination[sampleIndex++] = buffer.get(rowStart + x * pixelStride)
                }
            }
        }
        return true
    }
}
