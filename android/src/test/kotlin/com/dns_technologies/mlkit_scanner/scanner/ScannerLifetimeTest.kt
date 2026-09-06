package com.dns_technologies.mlkit_scanner.scanner

import androidx.lifecycle.LifecycleOwner
import com.dns_technologies.mlkit_scanner.PluginError
import com.dns_technologies.mlkit_scanner.scanner.components.analyzer.ImageBarcodeAnalyzer
import com.dns_technologies.mlkit_scanner.scanner.components.camera.Camera
import com.dns_technologies.mlkit_scanner.scanner.components.camera.CameraFrame
import com.dns_technologies.mlkit_scanner.scanner.components.camera.OnCameraFrame
import com.dns_technologies.mlkit_scanner.scanner.components.camera.Rect
import com.dns_technologies.mlkit_scanner.scanner.models.Barcode
import java.util.concurrent.ExecutorService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify

internal class ScannerLifetimeTest {
    @Test
    fun `disposal releases each component only once`() = withScanner { f ->
        f.scanner.dispose()
        f.scanner.dispose()

        verify(f.camera).dispose()
        verify(f.analyzer).dispose()
        assertTrue(f.executor.isShutdown)
    }

    @Test
    fun `camera cleanup failure does not skip executor or analyzer cleanup`() = withScanner { f ->
        val failure = IllegalStateException("Camera cleanup failed")
        doThrow(failure).`when`(f.camera).dispose()

        assertSame(failure, runCatching { f.scanner.dispose() }.exceptionOrNull())
        assertTrue(f.executor.isShutdown)
        verify(f.analyzer).dispose()
        f.scanner.dispose()
        verify(f.camera).dispose()
    }

    @Test
    fun `cleanup preserves the first failure and suppresses the later failure`() = withScanner { f ->
        val first = IllegalStateException("Camera cleanup failed")
        val second = IllegalStateException("Analyzer cleanup failed")
        doThrow(first).`when`(f.camera).dispose()
        doThrow(second).`when`(f.analyzer).dispose()

        val error = runCatching { f.scanner.dispose() }.exceptionOrNull()

        assertSame(first, error)
        assertEquals(listOf(second), first.suppressed.toList())
        assertTrue(f.executor.isShutdown)
    }

    @Test
    fun `released scanner rejects camera restart and new subscriptions`() = withScanner { f ->
        f.scanner.dispose()

        assertSame(PluginError.CameraSessionDisposed, runCatching { f.startCamera() }.exceptionOrNull())
        assertSame(PluginError.CameraSessionDisposed,
            runCatching { f.scanner.subscribeToScanResults {} }.exceptionOrNull())
        assertEquals(1, f.bindCount)
    }

    @Test
    fun `released scanner cannot resume analysis of already queued frames`() = withScanner { f ->
        f.scanner.dispose()
        f.scanner.resumeScan()
        f.scanner.startScan(0)

        f.emitFrame()

        verify(f.analyzer, never()).analyze(f.frame, BOUNDS)
    }

    @Test
    fun `pause and resume inside first callback suppress remaining old-run deliveries`() = withScanner { f ->
        val received = mutableListOf<String>()
        f.scanner.subscribeToScanResults {
            received += "first"
            f.scanner.pauseScan()
            f.scanner.resumeScan()
        }
        f.scanner.subscribeToScanResults { received += "second" }

        f.emitFrame()

        assertEquals(listOf("first"), received)
    }

    @Test
    fun `disposal inside first callback suppresses remaining deliveries`() = withScanner { f ->
        val received = mutableListOf<String>()
        f.scanner.subscribeToScanResults {
            received += "first"
            f.scanner.dispose()
        }
        f.scanner.subscribeToScanResults { received += "second" }

        f.emitFrame()

        assertEquals(listOf("first"), received)
    }

    @Test
    fun `cancelling a later listener inside a callback skips it in the current result`() = withScanner { f ->
        val received = mutableListOf<String>()
        lateinit var second: ScanResultSubscription
        f.scanner.subscribeToScanResults {
            received += "first"
            second.cancel()
        }
        second = f.scanner.subscribeToScanResults { received += "second" }

        f.emitFrame()

        assertEquals(listOf("first"), received)
    }

    @Test
    fun `repeated camera starts reuse the owned serial executor`() = withScanner { f ->
        val executor = f.executor

        f.startCamera()

        assertSame(executor, f.executor)
        assertEquals(2, f.bindCount)
    }

    @Test
    fun `cleanup tolerates the same exception from multiple resources`() = withScanner { f ->
        val failure = IllegalStateException("Shared cleanup error")
        doThrow(failure).`when`(f.camera).dispose()
        doThrow(failure).`when`(f.analyzer).dispose()

        assertSame(failure, runCatching { f.scanner.dispose() }.exceptionOrNull())
        assertTrue(failure.suppressed.isEmpty())
        assertTrue(f.executor.isShutdown)
    }

    @Test
    fun `camera cleanup can reenter disposal without releasing anything twice`() = withScanner { f ->
        doAnswer { f.scanner.dispose() }.`when`(f.camera).dispose()

        f.scanner.dispose()

        verify(f.camera).dispose()
        verify(f.analyzer).dispose()
    }

    @Test
    fun `analyzer cleanup failure leaves the scanner terminal`() = withScanner { f ->
        val failure = IllegalStateException("Analyzer cleanup failed")
        doThrow(failure).`when`(f.analyzer).dispose()

        assertSame(failure, runCatching { f.scanner.dispose() }.exceptionOrNull())
        f.scanner.dispose()

        verify(f.analyzer).dispose()
        assertTrue(f.executor.isShutdown)
    }

    @Test
    fun `disposal rejects an in-flight result without waiting for recognition`() = withScanner { f ->
        val started = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        var deliveries = 0
        f.scanner.subscribeToScanResults { deliveries++ }
        doAnswer {
            started.countDown()
            assertTrue(finish.await(1, TimeUnit.SECONDS))
            BARCODE
        }.`when`(f.analyzer).analyze(f.frame, BOUNDS)
        try {
            val work = executor.submit { f.emitFrame() }
            assertTrue(started.await(1, TimeUnit.SECONDS))

            f.scanner.dispose()
            finish.countDown()
            work.get(1, TimeUnit.SECONDS)

            assertEquals(0, deliveries)
        } finally {
            finish.countDown()
            executor.shutdownNow()
        }
    }

    private fun withScanner(block: (Fixture) -> Unit) {
        val fixture = Fixture()
        try {
            block(fixture)
        } finally {
            runCatching { fixture.scanner.dispose() }
            fixture.executor.shutdownNow()
        }
    }

    private class Fixture {
        val camera = mock(Camera::class.java)
        val analyzer = mock(ImageBarcodeAnalyzer::class.java)
        val frame = mock(CameraFrame::class.java)
        val scanner = Scanner(camera, analyzer)
        lateinit var executor: ExecutorService
        lateinit var onFrame: OnCameraFrame
        var bindCount = 0

        init {
            doReturn(BOUNDS).`when`(frame).cropRect
            doReturn(BARCODE).`when`(analyzer).analyze(frame, BOUNDS)
            doAnswer {
                bindCount++
                executor = it.getArgument(1)
                onFrame = it.getArgument(2)
                null
            }.`when`(camera).bind(anyValue(), anyValue(), anyValue(), anyValue(), anyValue(), anyValue())
            startCamera()
            scanner.startScan(0)
        }

        fun startCamera() = scanner.startCamera(mock(LifecycleOwner::class.java), {}, {})
        fun emitFrame() = onFrame(frame)
    }

    private companion object {
        val BOUNDS = Rect(0, 0, 100, 100)
        val BARCODE = Barcode("value", "value", 1, 1)
        fun <T> anyValue(): T = any<T>()
    }
}
