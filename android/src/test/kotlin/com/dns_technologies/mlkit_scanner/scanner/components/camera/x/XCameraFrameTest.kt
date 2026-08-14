package com.dns_technologies.mlkit_scanner.scanner.components.camera.x

import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import com.dns_technologies.mlkit_scanner.scanner.components.camera.Rect
import com.dns_technologies.mlkit_scanner.scanner.utils.ByteArrayLease
import com.dns_technologies.mlkit_scanner.scanner.utils.ImageProxyNv21Converter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal class XCameraFrameTest {
    @Test
    fun `full frame is scoped to leased nv21 buffer`() {
        val converter = mock(ImageProxyNv21Converter::class.java)
        val imageProxy = imageProxy()
        val crop = Rect(0, 0, FRAME_WIDTH, FRAME_HEIGHT)
        val data = ByteArray(FRAME_WIDTH * FRAME_HEIGHT * 3 / 2)
        val releases = AtomicInteger()
        val lease = ByteArrayLease(data) { releases.incrementAndGet() }
        val expected = Any()
        var receivedArguments: List<Any>? = null
        var releasesInsideCallback = -1
        doReturn(crop).`when`(converter).normalizeCropRect(null, FRAME_WIDTH, FRAME_HEIGHT)
        doReturn(lease).`when`(converter).convert(imageProxy, crop)
        val frame = XCameraFrame(imageProxy, converter)

        val result = frame.useNv21(null) { bytes, width, height, rotation ->
            receivedArguments = listOf(bytes, width, height, rotation)
            releasesInsideCallback = releases.get()
            expected
        }

        assertSame(expected, result)
        assertEquals(listOf(data, FRAME_WIDTH, FRAME_HEIGHT, ROTATION_DEGREES), receivedArguments)
        assertEquals(0, releasesInsideCallback)
        assertEquals(1, releases.get())
        assertEquals(FRAME_WIDTH, frame.width)
        assertEquals(FRAME_HEIGHT, frame.height)

        frame.close()
        assertEquals(1, releases.get())
        verify(imageProxy).close()
    }

    @Test
    fun `roi is copied to scoped nv21 buffer`() {
        val converter = mock(ImageProxyNv21Converter::class.java)
        val imageProxy = imageProxy()
        val crop = Rect(2, 0, 6, 4)
        val data = ByteArray(crop.width * crop.height * 3 / 2)
        val lease = ByteArrayLease(data) {}
        var receivedArguments: List<Any>? = null
        doReturn(crop).`when`(converter).normalizeCropRect(crop, FRAME_WIDTH, FRAME_HEIGHT)
        doReturn(lease).`when`(converter).convert(imageProxy, crop)
        val frame = XCameraFrame(imageProxy, converter)

        frame.useNv21(crop) { bytes, width, height, rotation ->
            receivedArguments = listOf(bytes, width, height, rotation)
        }

        assertEquals(listOf(data, crop.width, crop.height, ROTATION_DEGREES), receivedArguments)
        verify(converter).convert(imageProxy, crop)
        frame.close()
    }

    @Test
    fun `failed callback releases nv21 lease`() {
        val converter = mock(ImageProxyNv21Converter::class.java)
        val imageProxy = imageProxy()
        val crop = Rect(2, 0, 6, 4)
        val releases = AtomicInteger()
        val lease = ByteArrayLease(ByteArray(24)) { releases.incrementAndGet() }
        doReturn(crop).`when`(converter).normalizeCropRect(crop, FRAME_WIDTH, FRAME_HEIGHT)
        doReturn(lease).`when`(converter).convert(imageProxy, crop)
        val frame = XCameraFrame(imageProxy, converter)

        val error = runCatching {
            frame.useNv21(crop) { _, _, _, _ -> error("analysis failed") }
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertEquals(1, releases.get())
        frame.close()
        assertEquals(1, releases.get())
    }

    @Test
    fun `frame closes image proxy once and rejects later access`() {
        val imageProxy = imageProxy()
        val frame = XCameraFrame(imageProxy, mock(ImageProxyNv21Converter::class.java))

        frame.close()
        frame.close()
        val error = runCatching {
            frame.useNv21(null) { _, _, _, _ -> }
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        verify(imageProxy, times(1)).close()
    }

    @Test
    fun `scoped frame closes image proxy when camera callback fails`() {
        val imageProxy = imageProxy()
        val frame = XCameraFrame(imageProxy, mock(ImageProxyNv21Converter::class.java))

        val error = runCatching {
            frame.use { error("callback failed") }
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        verify(imageProxy).close()
    }

    @Test
    fun `concurrent callers access frame only once`() {
        val converter = mock(ImageProxyNv21Converter::class.java)
        val imageProxy = imageProxy()
        val crop = Rect(0, 0, FRAME_WIDTH, FRAME_HEIGHT)
        val lease = ByteArrayLease(ByteArray(FRAME_WIDTH * FRAME_HEIGHT * 3 / 2)) {}
        doReturn(crop).`when`(converter).normalizeCropRect(null, FRAME_WIDTH, FRAME_HEIGHT)
        doReturn(lease).`when`(converter).convert(imageProxy, crop)
        val callbackStarted = CountDownLatch(1)
        val allowCallbackToFinish = CountDownLatch(1)
        val callbackCalls = AtomicInteger()
        val frame = XCameraFrame(imageProxy, converter)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val first = executor.submit<Result<Unit>> {
                runCatching {
                    frame.useNv21(null) { _, _, _, _ ->
                        callbackCalls.incrementAndGet()
                        callbackStarted.countDown()
                        allowCallbackToFinish.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                        Unit
                    }
                }
            }
            assertTrue(callbackStarted.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS))
            val second = executor.submit<Result<Unit>> {
                runCatching { frame.useNv21(null) { _, _, _, _ -> } }
            }

            allowCallbackToFinish.countDown()

            assertTrue(first.get(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS).isSuccess)
            assertTrue(
                second.get(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS).exceptionOrNull() is
                    IllegalStateException,
            )
            assertEquals(1, callbackCalls.get())
            verify(converter, times(1)).convert(imageProxy, crop)
        } finally {
            allowCallbackToFinish.countDown()
            executor.shutdownNow()
            frame.close()
        }
    }

    private fun imageProxy(): ImageProxy {
        val imageProxy = mock(ImageProxy::class.java)
        val imageInfo = mock(ImageInfo::class.java)
        doReturn(FRAME_WIDTH).`when`(imageProxy).width
        doReturn(FRAME_HEIGHT).`when`(imageProxy).height
        doReturn(imageInfo).`when`(imageProxy).imageInfo
        doReturn(ROTATION_DEGREES).`when`(imageInfo).rotationDegrees
        return imageProxy
    }

    private companion object {
        const val FRAME_WIDTH = 8
        const val FRAME_HEIGHT = 6
        const val ROTATION_DEGREES = 90
        const val TEST_TIMEOUT_MS = 1_000L
    }
}
