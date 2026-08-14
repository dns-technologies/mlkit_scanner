package com.dns_technologies.mlkit_scanner.scanner.components.camera.x

import android.media.Image
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import com.dns_technologies.mlkit_scanner.scanner.components.camera.Rect
import com.dns_technologies.mlkit_scanner.scanner.utils.ByteArrayLease
import com.dns_technologies.mlkit_scanner.scanner.utils.ImageProxyNv21Converter
import com.google.mlkit.vision.common.InputImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@ExperimentalGetImage
internal class XCameraFrameTest {
    @Test
    fun `full frame uses zero copy media image`() {
        val converter = mock(ImageProxyNv21Converter::class.java)
        val mediaImage = mock(Image::class.java)
        val imageProxy = imageProxy(mediaImage = mediaImage)
        val expected = mock(InputImage::class.java)
        var receivedImage: Image? = null
        var receivedRotation = -1
        val frame = XCameraFrame(
            imageProxy = imageProxy,
            nv21Converter = converter,
            fromMediaImage = { image, rotation ->
                receivedImage = image
                receivedRotation = rotation
                expected
            },
        )

        val result = frame.toInputImage(null)

        assertSame(expected, result)
        assertSame(mediaImage, receivedImage)
        assertEquals(ROTATION_DEGREES, receivedRotation)
        assertEquals(FRAME_WIDTH, frame.width)
        assertEquals(FRAME_HEIGHT, frame.height)
        verifyNoInteractions(converter)

        frame.close()
        verify(imageProxy).close()
    }

    @Test
    fun `explicit full crop uses zero copy media image`() {
        val converter = mock(ImageProxyNv21Converter::class.java)
        val imageProxy = imageProxy(mediaImage = mock(Image::class.java))
        val frame = XCameraFrame(imageProxy, converter, fromMediaImage = { _, _ -> inputImage() })

        frame.toInputImage(Rect(0, 0, FRAME_WIDTH, FRAME_HEIGHT))

        verifyNoInteractions(converter)
        frame.close()
    }

    @Test
    fun `roi is copied to leased nv21 buffer`() {
        val converter = mock(ImageProxyNv21Converter::class.java)
        val imageProxy = imageProxy(mediaImage = mock(Image::class.java))
        val crop = Rect(2, 0, 6, 4)
        val data = ByteArray(crop.width * crop.height * 3 / 2)
        val releases = AtomicInteger()
        val lease = ByteArrayLease(data) { releases.incrementAndGet() }
        val expected = inputImage()
        var receivedArguments: List<Any>? = null
        doReturn(crop).`when`(converter).normalizeCropRect(crop, FRAME_WIDTH, FRAME_HEIGHT)
        doReturn(lease).`when`(converter).convert(imageProxy, crop)
        val frame = XCameraFrame(
            imageProxy = imageProxy,
            nv21Converter = converter,
            fromByteArray = { bytes, width, height, rotation, format ->
                receivedArguments = listOf(bytes, width, height, rotation, format)
                expected
            },
        )

        val result = frame.toInputImage(crop)

        assertSame(expected, result)
        assertEquals(
            listOf(data, crop.width, crop.height, ROTATION_DEGREES, InputImage.IMAGE_FORMAT_NV21),
            receivedArguments,
        )
        assertEquals(0, releases.get())

        frame.close()

        assertEquals(1, releases.get())
        verify(imageProxy).close()
    }

    @Test
    fun `missing media image falls back to full nv21 frame`() {
        val converter = mock(ImageProxyNv21Converter::class.java)
        val imageProxy = imageProxy()
        val crop = Rect(0, 0, FRAME_WIDTH, FRAME_HEIGHT)
        val lease = ByteArrayLease(ByteArray(FRAME_WIDTH * FRAME_HEIGHT * 3 / 2)) {}
        doReturn(crop).`when`(converter).normalizeCropRect(null, FRAME_WIDTH, FRAME_HEIGHT)
        doReturn(lease).`when`(converter).convert(imageProxy, crop)
        var receivedSize: Pair<Int, Int>? = null
        val frame = XCameraFrame(
            imageProxy = imageProxy,
            nv21Converter = converter,
            fromByteArray = { _, width, height, _, _ ->
                receivedSize = Pair(width, height)
                inputImage()
            },
        )

        frame.toInputImage(null)

        assertEquals(Pair(FRAME_WIDTH, FRAME_HEIGHT), receivedSize)
        verify(converter).convert(imageProxy, crop)
        frame.close()
    }

    @Test
    fun `failed input image creation releases nv21 lease`() {
        val converter = mock(ImageProxyNv21Converter::class.java)
        val imageProxy = imageProxy()
        val crop = Rect(2, 0, 6, 4)
        val releases = AtomicInteger()
        val lease = ByteArrayLease(ByteArray(24)) { releases.incrementAndGet() }
        doReturn(crop).`when`(converter).normalizeCropRect(crop, FRAME_WIDTH, FRAME_HEIGHT)
        doReturn(lease).`when`(converter).convert(imageProxy, crop)
        val frame = XCameraFrame(
            imageProxy = imageProxy,
            nv21Converter = converter,
            fromByteArray = { _, _, _, _, _ -> error("input creation failed") },
        )

        val error = runCatching { frame.toInputImage(crop) }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertEquals(1, releases.get())
        frame.close()
        assertEquals(1, releases.get())
    }

    @Test
    fun `frame closes image proxy once and rejects later materialization`() {
        val imageProxy = imageProxy()
        val frame = XCameraFrame(imageProxy, mock(ImageProxyNv21Converter::class.java))

        frame.close()
        frame.close()
        val error = runCatching { frame.toInputImage(null) }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        verify(imageProxy, times(1)).close()
    }

    @Test
    fun `scoped frame closes image proxy when callback fails`() {
        val imageProxy = imageProxy()
        val frame = XCameraFrame(imageProxy, mock(ImageProxyNv21Converter::class.java))

        val error = runCatching {
            frame.use { error("callback failed") }
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        verify(imageProxy).close()
    }

    @Test
    fun `concurrent callers materialize frame only once`() {
        val imageProxy = imageProxy(mediaImage = mock(Image::class.java))
        val factoryStarted = CountDownLatch(1)
        val allowFactoryToFinish = CountDownLatch(1)
        val factoryCalls = AtomicInteger()
        val frame = XCameraFrame(
            imageProxy = imageProxy,
            nv21Converter = mock(ImageProxyNv21Converter::class.java),
            fromMediaImage = { _, _ ->
                factoryCalls.incrementAndGet()
                factoryStarted.countDown()
                allowFactoryToFinish.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                inputImage()
            },
        )
        val executor = Executors.newFixedThreadPool(2)

        try {
            val first = executor.submit<Result<InputImage>> { runCatching { frame.toInputImage(null) } }
            assertTrue(factoryStarted.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS))
            val second = executor.submit<Result<InputImage>> { runCatching { frame.toInputImage(null) } }

            allowFactoryToFinish.countDown()

            assertTrue(first.get(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS).isSuccess)
            assertTrue(
                second.get(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS).exceptionOrNull() is IllegalStateException,
            )
            assertEquals(1, factoryCalls.get())
        } finally {
            allowFactoryToFinish.countDown()
            executor.shutdownNow()
            frame.close()
        }
    }

    private fun imageProxy(mediaImage: Image? = null): ImageProxy {
        val imageProxy = mock(ImageProxy::class.java)
        val imageInfo = mock(ImageInfo::class.java)
        doReturn(FRAME_WIDTH).`when`(imageProxy).width
        doReturn(FRAME_HEIGHT).`when`(imageProxy).height
        doReturn(imageInfo).`when`(imageProxy).imageInfo
        doReturn(ROTATION_DEGREES).`when`(imageInfo).rotationDegrees
        if (mediaImage != null) {
            doReturn(mediaImage).`when`(imageProxy).image
        }
        return imageProxy
    }

    private fun inputImage(): InputImage = mock(InputImage::class.java)

    private companion object {
        const val FRAME_WIDTH = 8
        const val FRAME_HEIGHT = 6
        const val ROTATION_DEGREES = 90
        const val TEST_TIMEOUT_MS = 1_000L
    }
}
