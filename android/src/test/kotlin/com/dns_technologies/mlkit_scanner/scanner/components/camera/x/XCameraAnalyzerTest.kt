package com.dns_technologies.mlkit_scanner.scanner.components.camera.x

import android.graphics.Rect as AndroidRect
import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import com.dns_technologies.mlkit_scanner.scanner.components.camera.CameraFrame
import com.dns_technologies.mlkit_scanner.scanner.components.camera.Rect
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
internal class XCameraAnalyzerTest {
    @Test
    fun `frame callback borrows image until returning then it closes once`() = withCameraFixture { f ->
        val image = image()
        var borrowed: CameraFrame? = null
        f.onFrame = {
            borrowed = it
            verify(image, never()).close()
        }
        f.start()
        f.analyzer().analyze(image)

        val frame = requireNotNull(borrowed)
        verify(image).close()
        assertTrue(runCatching { frame.useNv21(null) { _, _, _, _ -> } }.isFailure)
        frame.close()
        verify(image).close()
    }

    @Test
    fun `frame callback may close early without double-close by adapter`() = withCameraFixture { f ->
        val image = image()
        f.onFrame = { it.close() }
        f.start()
        f.analyzer().analyze(image)
        verify(image).close()
    }

    @Test
    fun `callback exception still closes frame and does not break next frame`() = withCameraFixture { f ->
        val first = image()
        val second = image()
        var count = 0
        f.onFrame = {
            count++
            error("Consumer failed")
        }
        f.start()
        f.analyzer().analyze(first)
        f.analyzer().analyze(second)

        assertEquals(2, count)
        verify(first).close()
        verify(second).close()
        assertTrue(f.errors.isEmpty())
    }

    @Test
    fun `frame construction failure closes raw proxy without invoking consumer`() = withCameraFixture { f ->
        val image = image()
        var count = 0
        f.onFrame = { count++ }
        doThrow(IllegalStateException("Invalid metadata")).`when`(image).imageInfo
        f.start()
        f.analyzer().analyze(image)

        assertEquals(0, count)
        verify(image).close()
    }

    @Test
    fun `queued callback after unbind closes without constructing or delivering frame`() = withCameraFixture { f ->
        val image = image()
        var count = 0
        f.onFrame = { count++ }
        f.start()
        val queued = f.analyzer()
        f.camera.unbind()
        queued.analyze(image)

        assertEquals(0, count)
        verify(image).close()
        verify(image, never()).imageInfo
    }

    @Test
    fun `old analyzer cannot deliver into replacement binding`() = withCameraFixture { f ->
        val stale = image()
        val fresh = image()
        var count = 0
        f.onFrame = { count++ }
        f.start()
        val oldAnalyzer = f.analyzer()
        f.camera.unbind()
        f.start()
        oldAnalyzer.analyze(stale)
        f.analyzer().analyze(fresh)

        assertEquals(1, count)
        verify(stale).close()
        verify(stale, never()).imageInfo
        verify(fresh).close()
    }

    @Test
    fun `size-only layout updates crop used by next frame without rebinding`() = withCameraFixture { f ->
        val crops = mutableListOf<Rect>()
        f.onFrame = { crops += it.cropRect }
        f.start()
        f.layout(8, 6)
        f.analyzer().analyze(image())
        f.layout(1, 1)
        f.analyzer().analyze(image())

        assertEquals(listOf(
            Rect(0, 0, 8, 6),
            Rect(1, 0, 7, 6),
        ), crops)
        assertEquals(1, f.groups.size)
    }

    @Test
    fun `already admitted frame completes its scope across disposal while later callbacks are rejected`() = withCameraFixture { f ->
        val active = image()
        val late = image()
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        f.onFrame = {
            entered.countDown()
            assertTrue(finish.await(5, TimeUnit.SECONDS))
            assertEquals(8, it.width)
        }
        f.start()
        val analyzer = f.analyzer()
        try {
            val result = executor.submit { analyzer.analyze(active) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            f.camera.dispose()
            verify(active, never()).close()
            finish.countDown()
            result.get(5, TimeUnit.SECONDS)
            analyzer.analyze(late)

            verify(active).close()
            verify(late).close()
            verify(late, never()).imageInfo
        } finally {
            finish.countDown()
            executor.shutdownNow()
        }
    }

    private fun image(): ImageProxy {
        val image = mock(ImageProxy::class.java)
        val info = mock(ImageInfo::class.java)
        doReturn(info).`when`(image).imageInfo
        doReturn(8).`when`(image).width
        doReturn(6).`when`(image).height
        doReturn(AndroidRect(0, 0, 8, 6)).`when`(image).cropRect
        return image
    }
}
