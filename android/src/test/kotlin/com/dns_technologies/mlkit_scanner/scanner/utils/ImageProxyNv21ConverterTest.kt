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

        var dimensions: Pair<Int, Int>? = null
        val converted = ImageProxyNv21Converter().convert(
            image = image,
            cropRect = Rect(2, 0, 4, 4),
        ) { bytes, width, height ->
            dimensions = Pair(width, height)
            bytes.copyOf()
        }

        assertArrayEquals(
            byteArrayOf(2, 3, 12, 13, 22, 23, 32, 33, 51, 101, 61, 111),
            converted,
        )
        assertEquals(Pair(2, 4), dimensions)
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

        val converted = ImageProxyNv21Converter().convert(image, FULL_CROP) { bytes, _, _ ->
            bytes.copyOf()
        }

        assertArrayEquals(
            ByteArray(16) { it.toByte() } +
                byteArrayOf(50, 100, 51, 101, 60, 110, 61, 111),
            converted,
        )
        assertEquals(1, uBuffer.position())
        assertEquals(0, vBuffer.position())
    }

    @Test
    fun `converter reuses released buffer but not active buffer`() {
        val converter = ImageProxyNv21Converter()
        val image = emptyImage()

        var firstData: ByteArray? = null
        converter.convert(image, FULL_CROP) { first, _, _ ->
            firstData = first
            converter.convert(image, FULL_CROP) { second, _, _ ->
                assertNotSame(first, second)
            }
        }
        converter.convert(image, FULL_CROP) { third, _, _ ->
            assertSame(firstData, third)
        }
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

        val error = runCatching {
            converter.convert(invalidImage, FULL_CROP) { _, _, _ -> Unit }
        }.exceptionOrNull()

        assertTrue(error is RuntimeException)
        val recovered = pool.acquire(NV21_SIZE)
        assertSame(retainedData, recovered.data)
        recovered.close()
    }

    @Test
    fun `failed callback releases output buffer`() {
        val pool = ReusableByteArrayPool()
        val retained = pool.acquire(NV21_SIZE)
        val retainedData = retained.data
        retained.close()
        val converter = ImageProxyNv21Converter(pool)

        val error = runCatching {
            converter.convert(emptyImage(), FULL_CROP) { _, _, _ -> error("analysis failed") }
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        val recovered = pool.acquire(NV21_SIZE)
        assertSame(retainedData, recovered.data)
        recovered.close()
    }

    @Test
    fun `convert bounds and aligns crop for chroma subsampling`() {
        val image = imageProxy(
            y = plane(ByteArray(35), 7, 1),
            u = plane(ByteArray(12), 4, 1),
            v = plane(ByteArray(12), 4, 1),
            width = 7,
            height = 5,
        )

        val metadata = ImageProxyNv21Converter().convert(
            image = image,
            cropRect = Rect(-1, -3, 7, 5),
            block = { bytes, width, height -> listOf(width, height, bytes.size) },
        )

        assertEquals(
            listOf(6, 4, 36),
            metadata,
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
        width: Int = FRAME_WIDTH,
        height: Int = FRAME_HEIGHT,
    ): ImageProxy = mock(ImageProxy::class.java).also { image ->
        doReturn(arrayOf(y, u, v)).`when`(image).planes
        doReturn(width).`when`(image).width
        doReturn(height).`when`(image).height
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
        const val FRAME_WIDTH = 4
        const val FRAME_HEIGHT = 4
        const val NV21_SIZE = 24
    }
}
