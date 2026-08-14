package com.dns_technologies.mlkit_scanner.scanner.components.camera.x

import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import com.dns_technologies.mlkit_scanner.scanner.components.camera.Rect
import com.dns_technologies.mlkit_scanner.scanner.utils.ImageProxyNv21Converter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doAnswer
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
    fun `full frame is converted through scoped nv21 callback`() {
        val converter = mock(ImageProxyNv21Converter::class.java)
        val imageProxy = imageProxy()
        val data = ByteArray(FRAME_WIDTH * FRAME_HEIGHT * 3 / 2)
        val expected = Any()
        var receivedArguments: List<Any>? = null
        var receivedCrop: Rect? = Rect(0, 0, 2, 2)
        val conversionCalls = AtomicInteger()
        stubConversion(converter, data, FRAME_WIDTH, FRAME_HEIGHT) { receivedImage, cropRect ->
            assertSame(imageProxy, receivedImage)
            receivedCrop = cropRect
            conversionCalls.incrementAndGet()
        }
        val frame = XCameraFrame(imageProxy, converter)

        val result = frame.useNv21(null) { bytes, width, height, rotation ->
            receivedArguments = listOf(bytes, width, height, rotation)
            expected
        }

        assertSame(expected, result)
        assertEquals(listOf(data, FRAME_WIDTH, FRAME_HEIGHT, ROTATION_DEGREES), receivedArguments)
        assertEquals(null, receivedCrop)
        assertEquals(1, conversionCalls.get())
        assertEquals(FRAME_WIDTH, frame.width)
        assertEquals(FRAME_HEIGHT, frame.height)

        frame.close()
        verify(imageProxy).close()
    }

    @Test
    fun `roi is copied to scoped nv21 buffer`() {
        val converter = mock(ImageProxyNv21Converter::class.java)
        val imageProxy = imageProxy()
        val crop = Rect(2, 0, 6, 4)
        val data = ByteArray(crop.width * crop.height * 3 / 2)
        var receivedArguments: List<Any>? = null
        var receivedCrop: Rect? = null
        stubConversion(converter, data, crop.width, crop.height) { receivedImage, cropRect ->
            assertSame(imageProxy, receivedImage)
            receivedCrop = cropRect
        }
        val frame = XCameraFrame(imageProxy, converter)

        frame.useNv21(crop) { bytes, width, height, rotation ->
            receivedArguments = listOf(bytes, width, height, rotation)
        }

        assertEquals(listOf(data, crop.width, crop.height, ROTATION_DEGREES), receivedArguments)
        assertEquals(crop, receivedCrop)
        frame.close()
    }

    @Test
    fun `failed callback is propagated and frame cannot be materialized again`() {
        val converter = mock(ImageProxyNv21Converter::class.java)
        val imageProxy = imageProxy()
        val crop = Rect(2, 0, 6, 4)
        val conversionCalls = AtomicInteger()
        stubConversion(converter, ByteArray(24), crop.width, crop.height) { _, _ ->
            conversionCalls.incrementAndGet()
        }
        val frame = XCameraFrame(imageProxy, converter)

        val error = runCatching {
            frame.useNv21(crop) { _, _, _, _ -> error("analysis failed") }
        }.exceptionOrNull()
        val repeatedAccessError = runCatching {
            frame.useNv21(crop) { _, _, _, _ -> Unit }
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertTrue(repeatedAccessError is IllegalStateException)
        assertEquals(1, conversionCalls.get())
        frame.close()
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
        val callbackStarted = CountDownLatch(1)
        val allowCallbackToFinish = CountDownLatch(1)
        val callbackCalls = AtomicInteger()
        val conversionCalls = AtomicInteger()
        stubConversion(
            converter = converter,
            data = ByteArray(FRAME_WIDTH * FRAME_HEIGHT * 3 / 2),
            width = FRAME_WIDTH,
            height = FRAME_HEIGHT,
            onConvert = { _, _ -> conversionCalls.incrementAndGet() },
        )
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
            assertEquals(1, conversionCalls.get())
        } finally {
            allowCallbackToFinish.countDown()
            executor.shutdownNow()
            frame.close()
        }
    }

    private fun stubConversion(
        converter: ImageProxyNv21Converter,
        data: ByteArray,
        width: Int,
        height: Int,
        onConvert: (ImageProxy, Rect?) -> Unit = { _, _ -> },
    ) {
        doAnswer { invocation ->
            val image = invocation.getArgument<ImageProxy>(0)
            val cropRect = invocation.getArgument<Rect?>(1)
            @Suppress("UNCHECKED_CAST")
            val block = invocation.arguments[2] as (ByteArray, Int, Int) -> Any?
            onConvert(image, cropRect)
            block(data, width, height)
        }.`when`(converter).convert<Any?>(
            anyValue(),
            anyValue(),
            anyValue(),
        )
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

        @Suppress("UNCHECKED_CAST")
        fun <T> anyValue(): T {
            any<T>()
            return null as T
        }
    }
}
