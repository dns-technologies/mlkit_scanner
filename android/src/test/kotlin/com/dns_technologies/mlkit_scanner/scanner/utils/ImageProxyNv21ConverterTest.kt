package com.dns_technologies.mlkit_scanner.scanner.utils

import androidx.camera.core.ImageProxy
import com.dns_technologies.mlkit_scanner.scanner.components.camera.Rect
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import java.nio.ByteBuffer

internal class ImageProxyNv21ConverterTest {
    @Test
    fun `converter copies only requested planar roi`() {
        val image = imageProxy(
            y = plane(
                bytes = byteArrayOf(
                    0, 1, 2, 3, 99, 99,
                    10, 11, 12, 13, 99, 99,
                    20, 21, 22, 23, 99, 99,
                    30, 31, 32, 33, 99, 99,
                ),
                rowStride = 6,
                pixelStride = 1,
            ),
            u = plane(byteArrayOf(100, 101, 99, 110, 111, 99), 3, 1),
            v = plane(byteArrayOf(50, 51, 99, 60, 61, 99), 3, 1),
        )

        val converted = ImageProxyNv21Converter().convert(image, Rect(2, 0, 4, 4))

        assertArrayEquals(
            byteArrayOf(2, 3, 12, 13, 22, 23, 32, 33, 51, 101, 61, 111),
            converted.data,
        )
        converted.close()
    }

    @Test
    fun `converter handles overlapping camera x chroma buffers`() {
        val chroma = byteArrayOf(50, 100, 51, 101, 60, 110, 61, 111)
        val vBuffer = ByteBuffer.wrap(chroma).apply { limit(chroma.size - 1) }
        val uBuffer = ByteBuffer.wrap(chroma).apply { position(1) }
        val image = imageProxy(
            y = plane(ByteArray(16) { it.toByte() }, 4, 1),
            u = plane(uBuffer, 4, 2),
            v = plane(vBuffer, 4, 2),
        )

        val converted = ImageProxyNv21Converter().convert(image, FULL_CROP)

        assertArrayEquals(
            ByteArray(16) { it.toByte() } +
                byteArrayOf(50, 100, 51, 101, 60, 110, 61, 111),
            converted.data,
        )
        assertEquals(1, uBuffer.position())
        assertEquals(0, vBuffer.position())
        converted.close()
    }

    @Test
    fun `converter reuses released buffer but not active buffer`() {
        val converter = ImageProxyNv21Converter()
        val image = emptyImage()

        val first = converter.convert(image, FULL_CROP)
        val second = converter.convert(image, FULL_CROP)

        assertNotSame(first.data, second.data)
        first.close()
        val third = converter.convert(image, FULL_CROP)
        assertSame(first.data, third.data)

        second.close()
        third.close()
    }

    @Test
    fun `failed conversion releases output buffer`() {
        val pool = ReusableByteArrayPool()
        val retained = pool.acquire(NV21_SIZE)
        val retainedData = retained.data
        retained.close()
        val converter = ImageProxyNv21Converter(pool)
        val invalidImage = imageProxy(
            y = plane(ByteArray(1), 4, 1),
            u = plane(ByteArray(1), 2, 1),
            v = plane(ByteArray(1), 2, 1),
        )

        val error = runCatching { converter.convert(invalidImage, FULL_CROP) }.exceptionOrNull()

        assertTrue(error is RuntimeException)
        val recovered = pool.acquire(NV21_SIZE)
        assertSame(retainedData, recovered.data)
        recovered.close()
    }

    @Test
    fun `crop is bounded and aligned for chroma subsampling`() {
        assertEquals(
            Rect(0, 0, 6, 4),
            ImageProxyNv21Converter().normalizeCropRect(
                cropRect = Rect(-1, -3, 7, 5),
                width = 7,
                height = 5,
            ),
        )
    }

    private fun emptyImage(): ImageProxy = imageProxy(
        y = plane(ByteArray(16), 4, 1),
        u = plane(ByteArray(4), 2, 1),
        v = plane(ByteArray(4), 2, 1),
    )

    private fun imageProxy(
        y: ImageProxy.PlaneProxy,
        u: ImageProxy.PlaneProxy,
        v: ImageProxy.PlaneProxy,
    ): ImageProxy = mock(ImageProxy::class.java).also { image ->
        doReturn(arrayOf(y, u, v)).`when`(image).planes
    }

    private fun plane(
        bytes: ByteArray,
        rowStride: Int,
        pixelStride: Int,
    ): ImageProxy.PlaneProxy = plane(ByteBuffer.wrap(bytes), rowStride, pixelStride)

    private fun plane(
        buffer: ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
    ): ImageProxy.PlaneProxy = mock(ImageProxy.PlaneProxy::class.java).also { plane ->
        doReturn(buffer).`when`(plane).buffer
        doReturn(rowStride).`when`(plane).rowStride
        doReturn(pixelStride).`when`(plane).pixelStride
    }

    private companion object {
        val FULL_CROP = Rect(0, 0, 4, 4)
        const val NV21_SIZE = 24
    }
}
