package com.dns_technologies.mlkit_scanner.scanner.components.camera.x

import android.graphics.Rect as AndroidRect
import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import com.dns_technologies.mlkit_scanner.scanner.components.camera.Rect
import com.dns_technologies.mlkit_scanner.scanner.utils.ImageProxyNv21Converter
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify

// region XCameraFrameTest
internal class XCameraFrameTest {
    @Test
    fun `conversion failure consumes the only access attempt`() {
        val converter = mock(ImageProxyNv21Converter::class.java)
        val image = imageProxy()
        val failure = IllegalArgumentException("Invalid image planes")
        stubConversion(converter, ByteArray(0), 0, 0) { _, _ -> throw failure }
        val frame = createFrame(image, converter)

        assertSame(failure, runCatching { frame.useNv21(null) { _, _, _, _ -> } }.exceptionOrNull())
        assertTrue(runCatching { frame.useNv21(null) { _, _, _, _ -> } }.exceptionOrNull() is IllegalStateException)
        frame.close()
        verify(image).close()
    }

    @Test
    fun `frame snapshots mutable CameraX metadata`() {
        val crop = cameraCropRect(0, 0, 8, 6)
        val image = imageProxy(crop)
        val info = image.imageInfo
        val frame = createFrame(image, mock(ImageProxyNv21Converter::class.java))
        crop.right = 2
        doReturn(2).`when`(image).width
        doReturn(180).`when`(info).rotationDegrees

        assertEquals(Rect(0, 0, 8, 6), frame.cropRect)
        assertEquals(8, frame.width)
        assertEquals(90, frame.rotationDegree)
        frame.close()
    }

    @Test
    fun `failed proxy close is not attempted twice`() {
        val image = imageProxy()
        val failure = IllegalStateException("Image close failed")
        doThrow(failure).`when`(image).close()
        val frame = createFrame(image, mock(ImageProxyNv21Converter::class.java))

        assertSame(failure, runCatching { frame.close() }.exceptionOrNull())
        frame.close()
        assertTrue(runCatching { frame.useNv21(null) { _, _, _, _ -> } }.isFailure)
        verify(image).close()
    }

    @Test
    fun `close from another thread waits until scoped access has returned`() {
        val converter = mock(ImageProxyNv21Converter::class.java)
        val image = imageProxy()
        stubConversion(converter, ByteArray(72), 8, 6)
        val frame = createFrame(image, converter)
        val closeRequested = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val closing = frame.useNv21(null) { _, _, _, _ ->
                val result = executor.submit {
                    closeRequested.countDown()
                    frame.close()
                }
                assertTrue(closeRequested.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS))
                assertFalse(result.isDone)
                verify(image, org.mockito.Mockito.never()).close()
                result
            }
            closing.get(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            verify(image).close()
        } finally {
            executor.shutdownNow()
            frame.close()
        }
    }

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
        val frame = createFrame(imageProxy, converter)

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
        assertEquals(Rect(0, 0, FRAME_WIDTH, FRAME_HEIGHT), frame.cropRect)

        frame.close()
        verify(imageProxy).close()
    }

    @Test
    fun `frame preserves CameraX crop coordinates`() {
        val crop = cameraCropRect(2, 1, 7, 5)
        val imageProxy = imageProxy(crop)

        val frame = createFrame(imageProxy, mock(ImageProxyNv21Converter::class.java))

        assertEquals(Rect(crop.left, crop.top, crop.right, crop.bottom), frame.cropRect)
        frame.close()
        verify(imageProxy).close()
    }

    @Test
    fun `frame crop follows resized fill-center preview`() {
        val imageProxy = imageProxy(cameraCropRect(0, 0, 8, 6))
        val frame = XCameraFrame(
            imageProxy = imageProxy,
            nv21Converter = mock(ImageProxyNv21Converter::class.java),
            previewWidth = 1,
            previewHeight = 1,
        )

        assertEquals(Rect(1, 0, 7, 6), frame.cropRect)
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
        val frame = createFrame(imageProxy, converter)

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
        val frame = createFrame(imageProxy, converter)

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
        val frame = createFrame(imageProxy, mock(ImageProxyNv21Converter::class.java))

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
        val frame = createFrame(imageProxy, mock(ImageProxyNv21Converter::class.java))

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
        val frame = createFrame(imageProxy, converter)
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

    private fun imageProxy(
        cropRect: AndroidRect = cameraCropRect(0, 0, FRAME_WIDTH, FRAME_HEIGHT),
    ): ImageProxy {
        val imageProxy = mock(ImageProxy::class.java)
        val imageInfo = mock(ImageInfo::class.java)
        doReturn(FRAME_WIDTH).`when`(imageProxy).width
        doReturn(FRAME_HEIGHT).`when`(imageProxy).height
        doReturn(cropRect).`when`(imageProxy).cropRect
        doReturn(imageInfo).`when`(imageProxy).imageInfo
        doReturn(ROTATION_DEGREES).`when`(imageInfo).rotationDegrees
        return imageProxy
    }

    private fun createFrame(
        imageProxy: ImageProxy,
        converter: ImageProxyNv21Converter,
    ): XCameraFrame {
        val cropRect = imageProxy.cropRect
        return XCameraFrame(
            imageProxy = imageProxy,
            nv21Converter = converter,
            previewWidth = cropRect.bottom - cropRect.top,
            previewHeight = cropRect.right - cropRect.left,
        )
    }

    private fun cameraCropRect(left: Int, top: Int, right: Int, bottom: Int) =
        AndroidRect().apply {
            this.left = left
            this.top = top
            this.right = right
            this.bottom = bottom
        }

    private companion object {
        const val FRAME_WIDTH = 8
        const val FRAME_HEIGHT = 6
        const val ROTATION_DEGREES = 90
        const val TEST_TIMEOUT_MS = 1_000L

        fun <T> anyValue(): T = any<T>()
    }
}
// endregion

// region XCameraFrameCropTest
internal class XCameraFrameCropTest {
    private val converter = mock(ImageProxyNv21Converter::class.java)

    @Test
    fun `narrow preview keeps a representable non-empty crop inside the source`() {
        val source = Rect(10, 20, 12, 30)
        val crop = cropFor(source, 0, 1, 100)

        assertFalse(crop.isEmpty)
        assertEquals(source.top, crop.top)
        assertEquals(source.bottom, crop.bottom)
    }

    @Test
    fun `crop stays centered non-empty and inside source across geometry and rotations`() {
        val sizes = listOf(1, 2, 3, 7, 90, 901, 1600)
        for (width in sizes) for (height in sizes) {
            val source = Rect(10, 20, 10 + width, 20 + height)
            for (previewWidth in sizes) for (previewHeight in sizes) {
                for (rotation in listOf(0, 90, 180, 270)) {
                    val crop = cropFor(source, rotation, previewWidth, previewHeight)
                    assertFalse("$source -> $crop at $rotation", crop.isEmpty)
                    assertTrue(crop.left >= source.left && crop.right <= source.right)
                    assertTrue(crop.top >= source.top && crop.bottom <= source.bottom)
                    assertEquals(source.left + source.right, crop.left + crop.right)
                    assertEquals(source.top + source.bottom, crop.top + crop.bottom)
                    assertTrue(crop.width == source.width || crop.height == source.height)
                }
            }
        }
    }

    @Test
    fun `rotating preview and frame together preserves source crop`() {
        val source = Rect(10, 20, 913, 1621)
        val crop = cropFor(source, 0, 300, 900)
        assertEquals(crop, cropFor(source, 180, 300, 900))
        assertEquals(crop, cropFor(source, 90, 900, 300))
        assertEquals(crop, cropFor(source, 270, 900, 300))
    }

    @Test
    fun `empty source and negative preview dimensions are preserved`() {
        val empty = Rect(10, 20, 10, 30)
        val source = Rect(0, 0, 100, 200)
        assertEquals(empty, cropFor(empty, 90, 100, 100))
        assertEquals(source, cropFor(source, 90, -100, 100))
    }

    @Test
    fun `matching preview aspect preserves source crop for every rotation`() {
        val source = Rect(10, 20, 1610, 920)

        assertEquals(source, cropFor(source, 0, 1600, 900))
        assertEquals(source, cropFor(source, 90, 900, 1600))
        assertEquals(source, cropFor(source, 180, 1600, 900))
        assertEquals(source, cropFor(source, 270, 900, 1600))
    }

    @Test
    fun `square preview crops horizontal source bounds for unrotated frame`() {
        assertEquals(
            Rect(350, 0, 1250, 900),
            cropFor(Rect(0, 0, 1600, 900), 0, 1000, 1000),
        )
    }

    @Test
    fun `resized preview preserves non-zero source origin`() {
        assertEquals(
            Rect(450, 200, 1350, 1100),
            cropFor(Rect(100, 200, 1700, 1100), 0, 1000, 1000),
        )
    }

    @Test
    fun `square preview crops vertical source bounds for unrotated frame`() {
        assertEquals(
            Rect(0, 350, 900, 1250),
            cropFor(Rect(0, 0, 900, 1600), 0, 1000, 1000),
        )
    }

    @Test
    fun `rotated frame maps preview vertical inset onto source horizontal axis`() {
        assertEquals(
            Rect(350, 0, 1250, 900),
            cropFor(Rect(0, 0, 1600, 900), 90, 1000, 1000),
        )
    }

    @Test
    fun `missing preview bounds preserve CameraX crop`() {
        val source = Rect(10, 20, 1610, 920)

        assertEquals(source, cropFor(source, 0, 0, 900))
        assertEquals(source, cropFor(source, 0, 1600, 0))
    }

    private fun cropFor(source: Rect, rotationDegrees: Int, previewWidth: Int, previewHeight: Int): Rect {
        val crop = mock(AndroidRect::class.java).apply {
            left = source.left
            top = source.top
            right = source.right
            bottom = source.bottom
        }
        val imageInfo = mock(ImageInfo::class.java)
        doReturn(rotationDegrees).`when`(imageInfo).rotationDegrees
        val image = mock(ImageProxy::class.java)
        doReturn(imageInfo).`when`(image).imageInfo
        doReturn(crop).`when`(image).cropRect
        doReturn(source.right).`when`(image).width
        doReturn(source.bottom).`when`(image).height
        return XCameraFrame(image, converter, previewWidth, previewHeight).use { it.cropRect }
    }
}
// endregion
