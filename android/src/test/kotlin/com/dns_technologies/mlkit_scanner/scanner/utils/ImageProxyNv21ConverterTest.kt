package com.dns_technologies.mlkit_scanner.scanner.utils

import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import com.dns_technologies.mlkit_scanner.scanner.components.camera.Rect
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import java.nio.ByteBuffer

internal class ImageProxyNv21ConverterTest {
    @Test
    fun `converter copies only requested planar roi`() {
        val image = imageProxy(
            width = 4,
            height = 4,
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
        val converted = ImageProxyNv21Converter().convert(
            image,
            Rect(2, 0, 4, 4),
        )

        assertArrayEquals(
            byteArrayOf(2, 3, 12, 13, 22, 23, 32, 33, 51, 101, 61, 111),
            converted.data,
        )
        converted.close()
    }

    @Test
    fun `converter handles interleaved chroma pixel stride`() {
        val image = imageProxy(
            width = 4,
            height = 4,
            y = plane(ByteArray(16) { it.toByte() }, 4, 1),
            u = plane(byteArrayOf(100, 0, 101, 0, 110, 0, 111, 0), 4, 2),
            v = plane(byteArrayOf(50, 0, 51, 0, 60, 0, 61, 0), 4, 2),
        )
        val converted = ImageProxyNv21Converter().convert(image, null)

        assertArrayEquals(
            ByteArray(16) { it.toByte() } + byteArrayOf(50, 100, 51, 101, 60, 110, 61, 111),
            converted.data,
        )
        converted.close()
    }

    @Test
    fun `converter reuses released buffer but not active buffer`() {
        val converter = ImageProxyNv21Converter()
        val image = imageProxy(
            width = 4,
            height = 4,
            y = plane(ByteArray(16), 4, 1),
            u = plane(ByteArray(4), 2, 1),
            v = plane(ByteArray(4), 2, 1),
        )
        val first = converter.convert(image, null)
        val second = converter.convert(image, null)

        assertNotSame(first.data, second.data)

        first.close()
        val third = converter.convert(image, null)
        assertSame(first.data, third.data)

        second.close()
        third.close()
    }

    @Test
    fun `converter replaces retained buffer when roi size changes`() {
        val converter = ImageProxyNv21Converter()
        val image = imageProxy(
            width = 4,
            height = 4,
            y = plane(ByteArray(16), 4, 1),
            u = plane(ByteArray(4), 2, 1),
            v = plane(ByteArray(4), 2, 1),
        )
        val fullFrame = converter.convert(image, null)
        val fullFrameData = fullFrame.data
        fullFrame.close()
        val cropped = converter.convert(image, Rect(0, 0, 2, 2))
        val croppedData = cropped.data
        cropped.close()
        val nextCropped = converter.convert(image, Rect(0, 0, 2, 2))

        assertNotSame(fullFrameData, croppedData)
        assertSame(croppedData, nextCropped.data)
        nextCropped.close()
    }

    private fun imageProxy(
        width: Int,
        height: Int,
        y: ImageProxy.PlaneProxy,
        u: ImageProxy.PlaneProxy,
        v: ImageProxy.PlaneProxy,
        rotationDegree: Int = 0,
    ): ImageProxy {
        val image = mock(ImageProxy::class.java)
        val imageInfo = mock(ImageInfo::class.java)
        doReturn(width).`when`(image).width
        doReturn(height).`when`(image).height
        doReturn(arrayOf(y, u, v)).`when`(image).planes
        doReturn(imageInfo).`when`(image).imageInfo
        doReturn(rotationDegree).`when`(imageInfo).rotationDegrees
        return image
    }

    private fun plane(
        bytes: ByteArray,
        rowStride: Int,
        pixelStride: Int,
    ): ImageProxy.PlaneProxy {
        val plane = mock(ImageProxy.PlaneProxy::class.java)
        doReturn(ByteBuffer.wrap(bytes)).`when`(plane).buffer
        doReturn(rowStride).`when`(plane).rowStride
        doReturn(pixelStride).`when`(plane).pixelStride
        return plane
    }
}
