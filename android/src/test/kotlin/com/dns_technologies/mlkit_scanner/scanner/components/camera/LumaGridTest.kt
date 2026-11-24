package com.dns_technologies.mlkit_scanner.scanner.components.camera

import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

internal class LumaGridTest {
    @Test
    fun `planes and NV21 sample the same crop with direct read-only padded input`() {
        val width = 128
        val height = 96
        val rowStride = 264
        val pixelStride = 2
        val offset = 7
        val plane = ByteBuffer.allocateDirect(offset + rowStride * height)
        val packed = ByteArray(width * height * 3 / 2)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val luminance = ((x + 3 * y) and 0xff).toByte()
                plane.put(offset + y * rowStride + x * pixelStride, luminance)
                packed[y * width + x] = luminance
            }
        }
        val bytes = plane.asReadOnlyBuffer()
        bytes.position(offset)
        val limit = offset + (height - 1) * rowStride + (width - 1) * pixelStride + 1
        bytes.limit(limit)
        val roi = Rect(2, 4, 66, 68)
        val fromPlane = ByteArray(LumaGrid.SAMPLE_COUNT)
        val fromNv21 = ByteArray(LumaGrid.SAMPLE_COUNT)

        assertTrue(LumaGrid.samplePlane(bytes, width, height, rowStride, pixelStride, roi, fromPlane))
        assertTrue(LumaGrid.samplePlane(ByteBuffer.wrap(packed), width, height, width, 1, roi, fromNv21))

        assertArrayEquals(fromNv21, fromPlane)
        assertEquals(30.toByte(), fromPlane[0])
        assertEquals(114.toByte(), fromPlane[64])
        assertEquals(offset, bytes.position())
        assertEquals(limit, bytes.limit())
    }

    @Test
    fun `ROI too small to sample without duplicate pixels leaves destination intact`() {
        val input = ByteArray(128 * 128)
        val output = ByteArray(LumaGrid.SAMPLE_COUNT) { 9 }

        assertFalse(LumaGrid.samplePlane(ByteBuffer.wrap(input), 128, 128, 128, 1, Rect(0, 0, 62, 64), output))

        assertArrayEquals(ByteArray(output.size) { 9 }, output)
    }

    @Test
    fun `truncated and incompatible luma buffers are unsupported`() {
        val output = ByteArray(LumaGrid.SAMPLE_COUNT)
        val buffer = ByteBuffer.allocate(5000)

        assertFalse(LumaGrid.samplePlane(buffer, 64, 64, 60, 1, null, output))
        buffer.limit(4000)
        assertFalse(LumaGrid.samplePlane(buffer, 64, 64, 64, 1, null, output))
    }
}
