package com.dns_technologies.mlkit_scanner.scanner

import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.dns_technologies.mlkit_scanner.CameraControlOperation
import com.dns_technologies.mlkit_scanner.PluginError
import com.dns_technologies.mlkit_scanner.scanner.components.analyzer.ImageBarcodeAnalyzer
import com.dns_technologies.mlkit_scanner.scanner.components.camera.Camera
import com.dns_technologies.mlkit_scanner.scanner.components.camera.CameraAvailability
import com.dns_technologies.mlkit_scanner.scanner.components.camera.CameraConnection
import com.dns_technologies.mlkit_scanner.scanner.components.camera.CameraFrame
import com.dns_technologies.mlkit_scanner.scanner.components.camera.OnCameraAvailabilityChanged
import com.dns_technologies.mlkit_scanner.scanner.components.camera.OnCameraFrame
import com.dns_technologies.mlkit_scanner.scanner.components.camera.OnError
import com.dns_technologies.mlkit_scanner.scanner.components.camera.OnInit
import com.dns_technologies.mlkit_scanner.scanner.components.camera.Rect
import com.dns_technologies.mlkit_scanner.scanner.models.Barcode
import com.dns_technologies.mlkit_scanner.scanner.models.RecognizeVisorCropRect
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.*
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

// region ScannerTest
internal class ScannerTest {
    @Test
    fun `paused scanner does not send camera frame to analyzer`() {
        val fixture = Fixture()
        fixture.scanner.captureForTest()
        fixture.scanner.startScan(periodMs = 100)
        fixture.scanner.pauseScan()

        fixture.emitFrame()

        assertEquals(0, fixture.materializedFrames)
        assertEquals(0, fixture.analyzer.acceptedAnalysisCalls)
        assertEquals(1, fixture.closedFrames)
    }

    @Test
    fun `analyzer materializes frames only when time based attempt is due`() {
        val fixture = Fixture()
        fixture.scanner.captureForTest()
        fixture.scanner.startScan(periodMs = 100)

        fixture.emitFrame()
        fixture.setCurrentTimeMs(FAILED_ANALYSIS_INTERVAL_MS - 1)
        fixture.emitFrame()
        fixture.setCurrentTimeMs(FAILED_ANALYSIS_INTERVAL_MS)
        fixture.emitFrame()

        assertEquals(2, fixture.materializedFrames)
        assertEquals(3, fixture.closedFrames)
        assertEquals(2, fixture.analyzer.acceptedAnalysisCalls)
    }

    @Test
    fun `scanner passes calculated crop to analyzer`() {
        val fixture = Fixture()
        val cropArea = RecognizeVisorCropRect(scaleWidth = 0.5, scaleHeight = 0.5)
        fixture.scanner.captureForTest()
        fixture.scanner.setCropArea(cropArea)
        fixture.scanner.startScan(periodMs = 100)

        fixture.emitFrame()

        assertEquals(Rect(180, 320, 540, 960), fixture.lastCropRect)
    }

    @Test
    fun `scanner analyzes only the CameraX preview crop when custom crop is absent`() {
        val fixture = Fixture(frameCropRect = Rect(10, 20, 710, 1260))
        fixture.scanner.captureForTest()
        fixture.scanner.startScan(periodMs = 0)

        fixture.emitFrame()

        assertEquals(Rect(10, 20, 710, 1260), fixture.lastCropRect)
    }

    @Test
    fun `scanner safely aligns odd CameraX preview crop when custom crop is absent`() {
        val fixture = Fixture(frameCropRect = Rect(11, 21, 709, 1259))
        fixture.scanner.captureForTest()
        fixture.scanner.startScan(periodMs = 0)

        fixture.emitFrame()

        assertEquals(Rect(12, 22, 708, 1258), fixture.lastCropRect)
    }

    @Test
    fun `scanner skips analyzer when crop is outside preview`() {
        val fixture = Fixture()
        fixture.scanner.captureForTest()
        fixture.scanner.setCropArea(
            RecognizeVisorCropRect(
                scaleWidth = 0.2,
                scaleHeight = 0.2,
                centerOffsetX = 3.0,
            ),
        )
        fixture.scanner.startScan(periodMs = 0)

        fixture.emitFrame()

        assertEquals(0, fixture.analyzer.acceptedAnalysisCalls)
        assertEquals(1, fixture.closedFrames)
    }

    @Test
    fun `zoom before capture is rejected by scanner`() = runBlocking {
        val fixture = Fixture()

        val error = runCatching { fixture.scanner.setZoomRatio(2.0F) }.exceptionOrNull()

        assertTrue(error is PluginError.CameraControlError)
        assertEquals(emptyList<Float>(), fixture.camera.zoomRatioValues)
    }

    @Test
    fun `zoomRatio is applied through the camera adapter`() = runBlocking {
        val fixture = Fixture()

        fixture.scanner.captureForTest()
        fixture.scanner.setZoomRatio(2.0F)

        assertEquals(listOf(1.0F, 2.0F), fixture.camera.zoomRatioValues)
    }

    @Test
    fun `resume scan preserves successful recognition cooldown`() {
        val fixture = Fixture(analysisResult = BARCODE)
        fixture.scanner.captureForTest()
        fixture.scanner.startScan(periodMs = 250)
        fixture.emitFrame()
        fixture.scanner.pauseScan()
        fixture.setCurrentTimeMs(249)

        fixture.scanner.startScan(250)
        fixture.emitFrame()

        assertEquals(1, fixture.analyzer.acceptedAnalysisCalls)

        fixture.setCurrentTimeMs(250)

        fixture.emitFrame()

        assertEquals(2, fixture.analyzer.acceptedAnalysisCalls)
    }

    @Test
    fun `start scan updates analyzer period`() {
        val fixture = Fixture(analysisResult = BARCODE)
        fixture.scanner.captureForTest()

        fixture.scanner.startScan(periodMs = 250)
        fixture.emitFrame()
        fixture.setCurrentTimeMs(249)
        fixture.emitFrame()

        assertEquals(1, fixture.analyzer.acceptedAnalysisCalls)

        fixture.setCurrentTimeMs(250)
        fixture.emitFrame()

        assertEquals(2, fixture.analyzer.acceptedAnalysisCalls)
    }

    @Test
    fun `cancelled scan job cannot publish its result after restart`() {
        val analysisStarted = CountDownLatch(1)
        val allowAnalysisToFinish = CountDownLatch(1)
        val analyzer = BlockingResultAnalyzer(analysisStarted, allowAnalysisToFinish)
        val camera = FakeCamera()
        val scanner = scannerForTest(camera, analyzer)
        val received = mutableListOf<Barcode>()
        scanner.subscribeToScanResults(received::add)
        scanner.captureForTest()
        scanner.startScan(periodMs = 0)
        val executor = Executors.newSingleThreadExecutor()

        try {
            val analysis = executor.submit { camera.emitFrame(testFrame()) }
            assertTrue(analysisStarted.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS))

            scanner.pauseScan()
            scanner.startScan(0)
            allowAnalysisToFinish.countDown()
            analysis.get(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)

            assertTrue(received.isEmpty())
        } finally {
            allowAnalysisToFinish.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `active scan job publishes analyzer result`() {
        val camera = FakeCamera()
        val scanner = scannerForTest(camera, ResultAnalyzer())
        val received = mutableListOf<Barcode>()
        scanner.subscribeToScanResults(received::add)
        scanner.captureForTest()
        scanner.startScan(periodMs = 0)

        camera.emitFrame(testFrame())

        assertEquals(listOf(BARCODE), received)
    }

    private class Fixture(
        private val frameCropRect: Rect = Rect(0, 0, 720, 1280),
        analysisResult: Barcode? = null,
    ) {
        private var currentTimeMs = 0L
        val camera = FakeCamera()
        val analyzer = FakeAnalyzer({ currentTimeMs }, analysisResult)
        val scanner = scannerForTest(camera, analyzer)
        var materializedFrames = 0
            private set
        var closedFrames = 0
            private set
        val lastCropRect: Rect?
            get() = analyzer.lastCropRect

        fun setCurrentTimeMs(value: Long) {
            currentTimeMs = value
        }

        fun emitFrame() {
            camera.emitFrame(
                object : CameraFrame {
                    override val width = 720
                    override val height = 1280
                    override val rotationDegree = 0
                    override val cropRect = frameCropRect

                    override fun <T> useNv21(
                        cropRect: Rect?,
                        block: (ByteArray, Int, Int, Int) -> T,
                    ): T {
                        materializedFrames += 1
                        return block(ByteArray(1), width, height, rotationDegree)
                    }

                    override fun close() {
                        closedFrames += 1
                    }
                },
            )
        }
    }

    private class FakeAnalyzer(
        currentTimeMs: () -> Long,
        private val analysisResult: Barcode?,
    ) : ImageBarcodeAnalyzer(currentTimeMs = currentTimeMs) {
        var acceptedAnalysisCalls = 0
            private set
        var lastCropRect: Rect? = null
            private set

        override fun analyzeFrame(frame: CameraFrame, cropRect: Rect?): Barcode? {
            lastCropRect = cropRect
            frame.useNv21(
                cropRect = cropRect,
                block = { _, _, _, _ -> acceptedAnalysisCalls += 1 },
            )
            return analysisResult
        }

        override fun disposeAnalyzer() = Unit
    }

    private class BlockingResultAnalyzer(
        private val analysisStarted: CountDownLatch,
        private val allowAnalysisToFinish: CountDownLatch,
    ) : ImageBarcodeAnalyzer({ 0L }) {
        override fun analyzeFrame(frame: CameraFrame, cropRect: Rect?): Barcode {
            analysisStarted.countDown()
            allowAnalysisToFinish.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            return BARCODE
        }

        override fun disposeAnalyzer() = Unit
    }

    private class ResultAnalyzer : ImageBarcodeAnalyzer({ 0L }) {
        override fun analyzeFrame(frame: CameraFrame, cropRect: Rect?): Barcode = BARCODE

        override fun disposeAnalyzer() = Unit
    }

    private class FakeCamera : Camera {
        override var onPreviewChanged: (Map<String, Any>?) -> Unit = {}
        override val disposal = CompletableDeferred<Unit>()
        override fun updateGeometry(size: android.util.Size) = Unit
        val zoomRatioValues = mutableListOf<Float>()
        private var onFrame: OnCameraFrame? = null

        override fun bind(
            lifecycleOwner: LifecycleOwner,
            analysisExecutor: ExecutorService,
            onFrame: OnCameraFrame,
            onAvailabilityChanged: OnCameraAvailabilityChanged,
            onInit: OnInit,
            onError: OnError,
        ) {
            this.onFrame = onFrame
            onInit()
            onAvailabilityChanged(com.dns_technologies.mlkit_scanner.scanner.components.camera.CameraAvailability.Open)
        }

        fun emitFrame(frame: CameraFrame) {
            frame.use { onFrame?.invoke(it) }
        }

        override fun isBound(): Boolean = onFrame != null

        override fun resetFocus(): Deferred<Unit> = completedControl()

        override fun focus(resetDelayMs: Long, x: Float, y: Float): Deferred<Unit> = completedControl()

        override fun setZoomRatio(ratio: Float): Deferred<Unit> {
            val result = completedControl()
            zoomRatioValues += ratio
            return result
        }

        override fun setTorch(enabled: Boolean): Deferred<Unit> = completedControl()

        private fun completedControl(): Deferred<Unit> {
            if (!isBound()) throw PluginError.CameraIsNotInitialized
            return CompletableDeferred(Unit)
        }

        override fun unbind() {
            onFrame = null
        }

        override fun dispose() {
            unbind()
            disposal.complete(Unit)
        }
    }

    private companion object {
        const val TEST_TIMEOUT_MS = 1_000L
        const val FAILED_ANALYSIS_INTERVAL_MS = 1_000L
        val BARCODE = Barcode(
            rawValue = "1234567890",
            displayValue = "1234567890",
            format = 1,
            valueType = 1,
        )

        fun testFrame(): CameraFrame = object : CameraFrame {
            override val width = 720
            override val height = 1280
            override val rotationDegree = 0

            override fun <T> useNv21(
                cropRect: Rect?,
                block: (ByteArray, Int, Int, Int) -> T,
            ): T = block(ByteArray(1), width, height, rotationDegree)

            override fun close() = Unit
        }
    }
}
// endregion

// region ScannerCaptureTest
/** Native tests cover physical work. Route selection and retained configuration are tested in Dart. */
internal class ScannerCaptureTest {
    @Test
    fun `capture applies all settings before binding focus and starting analysis`() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.activate()
            f.scanner.startScan(0)
            clearInvocations(f.camera, f.first)
            val zoom = CompletableDeferred<Unit>()
            val torch = CompletableDeferred<Unit>()
            f.zoom += zoom
            doReturn(torch).`when`(f.camera).setTorch(true)
            val configure = f.calls.async {
                f.scanner.select(f.first)
                f.scanner.capture(ScannerConfiguration(zoomRatio = 3F, torchEnabled = true))
            }
            zoom.complete(Unit)
            verify(f.camera).setTorch(true)
            torch.complete(Unit)
            configure.await()
            inOrder(f.camera, f.first).apply {
                verify(f.camera).setZoomRatio(3F)
                verify(f.camera).setTorch(true)
            }
        } finally { f.close() }
    }

    @Test
    fun `point controls keep preview focus and analysis running`() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.activate()
            f.scanner.startScan(0)
            clearInvocations(f.camera, f.first)
            val zoom = CompletableDeferred<Unit>()
            f.zoom += zoom
            val change = f.calls.async { f.scanner.setZoomRatio(3F) }
            f.emitFrame()
            f.drain()
            assertEquals(listOf(42 to BARCODE), f.results)
            zoom.complete(Unit)
            change.await()
            f.scanner.setTorch(true)
            f.scanner.setScanPeriod(250)
            f.scanner.setCropArea(com.dns_technologies.mlkit_scanner.scanner.models.RecognizeVisorCropRect(scaleWidth = 0.5))
            verify(f.camera, never()).resetFocus()
        } finally { f.close() }
    }

    @Test
    fun `binding and OPEN may arrive in either order`() = runBlocking<Unit> {
        for (openFirst in listOf(true, false)) {
            val f = Fixture()
            try {
                val capture = f.capture()
                if (openFirst) f.availability(CameraAvailability.Open) else f.initialized()
                assertFalse(capture.isCompleted)
                if (openFirst) f.initialized() else f.availability(CameraAvailability.Open)
                capture.await()
                verify(f.camera).setZoomRatio(1F)
                verify(f.camera).setTorch(false)
            } finally { f.close() }
        }
    }

    @Test
    fun `capture borrows Activity lifecycle and uses SDK readiness in STARTED state`() = runBlocking<Unit> {
        val f = Fixture()
        try {
            (f.host.lifecycle as LifecycleRegistry).currentState = Lifecycle.State.STARTED
            val capture = f.capture()
            assertSame(f.host.lifecycle, f.boundOwners.single().lifecycle)
            assertFalse(capture.isCompleted)
            f.open()
            capture.await()
            assertEquals(Lifecycle.State.STARTED, f.host.lifecycle.currentState)
        } finally { f.close() }
    }

    @Test
    fun `synchronous bind failure preserves primary error through failing cleanup`() = runBlocking<Unit> {
        val f = Fixture()
        val failure = IllegalStateException("binding")
        val cleanup = IllegalStateException("dispose")
        doThrow(failure).`when`(f.camera).bind(anyValue(), anyValue(), anyValue(), anyValue(), anyValue(), anyValue())
        doThrow(cleanup).`when`(f.camera).dispose()
        val result = f.capture()
        val error = runCatching { result.await() }.exceptionOrNull()
        assertEquals(failure.message, error?.message)
        assertTrue(failure.suppressed.contains(cleanup))
        assertTrue(f.scanner.isDisposed)
        f.close()
    }

    @Test
    fun `recovered OPEN permits a new capture after a temporary camera error`() = runBlocking<Unit> {
        val f = Fixture()
        try {
            val first = f.capture()
            f.initialized()
            f.availability(CameraAvailability.Closed(errorCode = 3))
            assertTrue(runCatching { first.await() }.exceptionOrNull() is PluginError.CameraControlError)
            f.availability(CameraAvailability.Open)
            f.capture().await()
        } finally { f.close() }
    }

    @Test
    fun `OPEN failure before binding acknowledgement completes capture`() = runBlocking<Unit> {
        val f = Fixture()
        try {
            val capture = f.capture()
            f.availability(CameraAvailability.Closed(errorCode = 3))
            assertTrue(capture.isCompleted)
            val failure = runCatching { capture.await() }.exceptionOrNull()
            assertTrue(failure is PluginError.CameraControlError)
            f.initialized()
            f.availability(CameraAvailability.Open)
            f.capture().await()
        } finally { f.close() }
    }

    @Test
    fun `capture waits for controls before acknowledging readiness`() = runBlocking<Unit> {
        val f = Fixture()
        try {
            val zoom = CompletableDeferred<Unit>()
            f.zoom += zoom
            val capture = f.capture()
            f.open()
            assertFalse(capture.isCompleted)
            verify(f.camera, never()).setTorch(false)
            zoom.complete(Unit)
            capture.await()
        } finally { f.close() }
    }

    @Test
    fun `release cancels actual SDK future and prevents remaining capture controls`() = runBlocking<Unit> {
        val f = Fixture()
        try {
            val zoom = CompletableDeferred<Unit>()
            f.zoom += zoom
            val capture = f.capture()
            f.open()
            f.scanner.releaseCamera()
            capture.await()
            assertTrue(zoom.isCancelled)
            verify(f.camera, never()).setTorch(false)
        } finally { f.close() }
    }

    @Test
    fun `Activity detach cancels SDK control and unbinds immediately while retaining adapters`() = runBlocking<Unit> {
        val f = Fixture()
        try {
            val zoom = CompletableDeferred<Unit>()
            f.zoom += zoom
            val capture = f.capture()
            f.open()

            f.scanner.detachActivity()
            capture.await()

            assertTrue(zoom.isCancelled)
            assertNull(f.scanner.viewId)
            assertFalse(f.scanner.isDisposed)
            verify(f.camera).unbind()
            verify(f.camera, never()).setTorch(false)
            verify(f.camera, never()).dispose()
            verify(f.analyzer, never()).dispose()
            assertTrue(f.delays.isEmpty())
            assertEquals(Lifecycle.State.RESUMED, f.host.lifecycle.currentState)
        } finally { f.close() }
    }

    @Test
    fun `next Dart capture rebinds replacement Activity and ignores old binding callbacks`() = runBlocking<Unit> {
        val f = Fixture()
        try {
            val oldCapture = f.capture()
            val oldInit = f.initialized
            val oldAvailability = f.availability
            val oldFailure = f.failed
            f.scanner.detachActivity()
            oldCapture.await()
            val replacement = mock(Lifecycle::class.java)

            f.scanner.attachActivity(replacement)
            assertEquals(1, f.boundOwners.size)
            f.scanner.select(f.second)
            val capture = f.calls.async {
                f.scanner.capture(ScannerConfiguration(zoomRatio = 4F, torchEnabled = true))
            }
            assertEquals(2, f.boundOwners.size)
            assertSame(replacement, f.boundOwners.last().lifecycle)

            oldInit()
            oldAvailability(CameraAvailability.Open)
            oldFailure(IllegalStateException("old Activity"))
            assertFalse(capture.isCompleted)
            assertFalse(f.scanner.isDisposed)
            f.initialized()
            assertFalse(capture.isCompleted)
            f.availability(CameraAvailability.Open)
            capture.await()

            verify(f.camera).setZoomRatio(4F)
            verify(f.camera).setTorch(true)
            assertEquals(43, f.scanner.viewId)
            verify(f.camera, never()).dispose()
        } finally { f.close() }
    }

    @Test
    fun `Activity detach forgets readiness of previously open camera`() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.activate()
            f.scanner.detachActivity()
            f.scanner.attachActivity(mock(Lifecycle::class.java))
            val capture = f.capture()
            assertEquals(2, f.boundOwners.size)
            f.initialized()
            assertFalse(capture.isCompleted)
            f.availability(CameraAvailability.Open)
            capture.await()
        } finally { f.close() }
    }

    @Test
    fun `caller cancellation also cancels its hardware future`() = runBlocking<Unit> {
        val f = Fixture()
        try {
            val zoom = CompletableDeferred<Unit>()
            f.zoom += zoom
            val capture = f.capture()
            f.open()
            capture.cancelAndJoin()
            assertTrue(zoom.isCancelled)
        } finally { f.close() }
    }

    @Test
    fun `release keeps SDK resources until Flutter requests disposal`() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.activate()
            f.scanner.releaseCamera()
            assertTrue(f.delays.isEmpty())
            assertEquals(Lifecycle.State.RESUMED, f.host.lifecycle.currentState)
            assertFalse(f.scanner.isDisposed)
            assertTrue(f.timers.isEmpty())
            f.scanner.dispose()
            assertTrue(f.scanner.isDisposed)
            assertEquals(Lifecycle.State.RESUMED, f.host.lifecycle.currentState)
            verify(f.camera).dispose()
        } finally { f.close() }
    }

    @Test
    fun `new consumer reuses SDK and cancels pending idle disposal`() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.activate()
            f.scanner.releaseCamera()
            f.scanner.select(f.second)
            assertFalse(f.scanner.isDisposed)
            assertEquals(43, f.scanner.viewId)
            assertTrue(f.timers.isEmpty())
        } finally { f.close() }
    }

    @Test
    fun `full snapshot is applied afresh on every capture`() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.activate()
            f.scanner.select(f.second)
            f.scanner.capture(ScannerConfiguration(zoomRatio = 4F, torchEnabled = true))
            verify(f.camera).setZoomRatio(4F)
            verify(f.camera).setTorch(true)
            f.scanner.startScan(120)
            f.emitFrame()
            verify(f.analyzer).analyze(anyValue(), anyValue())
        } finally { f.close() }
    }

    @Test
    fun `queued barcode is dropped after release and recapture of same view`() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.activate()
            f.scanner.startScan(0)
            f.emitFrame()
            assertTrue(f.posts.isNotEmpty())
            f.scanner.releaseCamera()
            f.scanner.select(f.first)
            f.scanner.capture(ScannerConfiguration())
            f.scanner.startScan(0)
            f.drain()
            assertTrue(f.results.isEmpty())
            f.emitFrame()
            f.drain()
            assertEquals(listOf(42 to BARCODE), f.results)
        } finally { f.close() }
    }

    @Test
    fun `cancel and restart replace delivery subscription without changing preview`() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.activate()
            f.scanner.startScan(0)
            f.emitFrame()
            f.scanner.pauseScan()
            f.scanner.startScan(0)
            f.drain()
            assertTrue(f.results.isEmpty())
            assertEquals(42, f.scanner.viewId)
            f.emitFrame()
            f.drain()
            assertEquals(listOf(42 to BARCODE), f.results)
        } finally { f.close() }
    }

    @Test
    fun `SDK CLOSED suppresses queued worker results`() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.activate()
            f.scanner.startScan(0)
            f.emitFrame()
            f.availability(CameraAvailability.Closed())
            f.drain()
            assertTrue(f.results.isEmpty())
        } finally { f.close() }
    }

    @Test
    fun `closing stops analysis and reopening cannot revive queued results`() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.activate()
            f.scanner.startScan(0)
            f.emitFrame()
            f.availability(CameraAvailability.Closed())
            f.emitFrame()
            verify(f.analyzer).analyze(anyValue(), anyValue())
            f.availability(CameraAvailability.Open)
            f.drain()
            assertTrue(f.results.isEmpty())
            f.emitFrame()
            f.drain()
            assertEquals(listOf(42 to BARCODE), f.results)
        } finally { f.close() }
    }

    @Test
    fun `startup failure releases hardware but not borrowed view`() = runBlocking<Unit> {
        val f = Fixture()
        try {
            val capture = f.capture()
            val failure = IllegalStateException("bind")
            f.failed(failure)
            // Await may copy ordinary exceptions to recover coroutine stack frames.
            val reported = runCatching { capture.await() }.exceptionOrNull()
            assertEquals(failure.javaClass, reported?.javaClass)
            assertEquals(failure.message, reported?.message)
            assertTrue(f.scanner.isDisposed)
            verify(f.camera).dispose()
            assertEquals(listOf(failure), f.released)
        } finally { f.close() }
    }

    @Test
    fun `cleanup failure does not skip terminal notification or view detachment`() = runBlocking<Unit> {
        val f = Fixture()
        f.activate()
        val failure = IllegalStateException("dispose")
        doThrow(failure).`when`(f.camera).dispose()
        assertSame(failure, runCatching { f.scanner.dispose() }.exceptionOrNull())
        assertTrue(f.scanner.isDisposed)
        assertFalse(f.scanner.scope.isActive)
        assertNull(f.scanner.viewId)
        assertEquals(1, f.released.size)
        f.scanner.dispose()
        verify(f.camera).dispose()
        f.close()
    }

    @Test
    fun `control failure carries operation and current view address`() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.activate()
            f.zoom += CompletableDeferred<Unit>().apply { completeExceptionally(IllegalStateException("zoom")) }
            val error = runCatching { f.scanner.setZoomRatio(2F) }.exceptionOrNull()
            assertTrue(error is PluginError.CameraControlError)
            error as PluginError.CameraControlError
            assertEquals(CameraControlOperation.ZOOM, error.operation)
            assertEquals(42, error.viewId)
        } finally { f.close() }
    }

    @Test
    fun `native focus does not cancel an outstanding Dart control`() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.activate()
            val pending = CompletableDeferred<Unit>()
            f.zoom += pending
            val command = f.calls.async { f.scanner.setZoomRatio(2F) }
            f.scanner.focus(100, 0F, 0F)
            assertFalse(pending.isCancelled)
            verify(f.camera, never()).focus(anyLong(), anyFloat(), anyFloat())
            pending.complete(Unit)
            command.await()
        } finally { f.close() }
    }

    @Test
    fun `new focus gesture supersedes a pending gesture without restarting preview`() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.activate()
            clearInvocations(f.camera)
            val pending = CompletableDeferred<Unit>()
            doReturn(pending, CompletableDeferred(Unit)).`when`(f.camera).focus(anyLong(), anyFloat(), anyFloat())
            f.scanner.focus(500L, 10F, 20F)
            f.scanner.focus(500L, 30F, 40F)
            assertTrue(pending.isCancelled)
            verify(f.camera).focus(500L, 30F, 40F)
            verify(f.camera, never()).resetFocus()
            verify(f.camera, never()).setZoomRatio(anyFloat())
            verify(f.camera, never()).setTorch(anyBoolean())
        } finally { f.close() }
    }

    @Test
    fun `release during SDK call cancels the returned future before admitting another control`() = runBlocking<Unit> {
        val f = Fixture()
        try {
            val pending = CompletableDeferred<Unit>()
            doAnswer {
                f.scanner.releaseCamera()
                pending
            }.`when`(f.camera).setZoomRatio(anyFloat())
            val capture = f.capture()
            f.open()
            capture.await()
            assertTrue(pending.isCancelled)
            verify(f.camera, never()).setTorch(false)
        } finally { f.close() }
    }

    @Test
    fun `SDK completion from worker thread preserves original control failure`() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.activate()
            val pending = CompletableDeferred<Unit>()
            val failure = IllegalStateException("SDK failure")
            f.zoom += pending
            val result = f.calls.async { runCatching { f.scanner.setZoomRatio(2F) }.exceptionOrNull() }
            withContext(Dispatchers.Default) { pending.completeExceptionally(failure) }
            val error = result.await() as PluginError.CameraControlError
            assertSame(failure, error.cause)
            assertEquals(CameraControlOperation.ZOOM, error.operation)
        } finally { f.close() }
    }

    @Test
    fun `SDK cancellation is a control error when the caller is still active`() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.activate()
            f.zoom += CompletableDeferred<Unit>().apply { cancel() }
            val error = runCatching { f.scanner.setZoomRatio(2F) }.exceptionOrNull()
            assertTrue(error is PluginError.CameraControlError)
            assertEquals(CameraControlOperation.ZOOM, (error as PluginError.CameraControlError).operation)
        } finally { f.close() }
    }

    @Test
    fun `focus uses current preview dimensions and offsets`() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.activate()
            val preview = f.first.size
            doReturn(100).`when`(preview).width
            doReturn(200).`when`(preview).height
            doReturn(CompletableDeferred(Unit)).`when`(f.camera).focus(anyLong(), anyFloat(), anyFloat())

            f.scanner.focus(3000L, 12F, -34F)
            verify(f.camera).focus(3000L, 62F, 66F)

            doReturn(101).`when`(preview).width
            doReturn(205).`when`(preview).height
            f.scanner.focus(3000L, 12F, -34F)
            verify(f.camera).focus(3000L, 62.5F, 68.5F)
        } finally { f.close() }
    }

    @Test
    fun `release does not schedule native cleanup`() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.activate()
            f.scanner.releaseCamera()
            f.scanner.releaseCamera()

            assertNull(f.scanner.viewId)
            assertTrue(f.delays.isEmpty())
            assertTrue(f.timers.isEmpty())
            verify(f.camera, never()).dispose()
            verify(f.analyzer, never()).dispose()
        } finally { f.close() }
    }

    @Test
    fun `release cancels a focus gesture before its SDK dispatch`() = runBlocking<Unit> {
        val queued = ArrayDeque<Runnable>()
        var deferDispatch = false
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                if (deferDispatch) queued += block else block.run()
            }
        }
        val f = Fixture(dispatcher)
        try {
            f.activate()
            deferDispatch = true
            f.scanner.focus(1000L, 0F, 0F)
            f.scanner.releaseCamera()
            f.scanner.select(f.second)
            while (queued.isNotEmpty()) queued.removeFirst().run()
            verify(f.camera, never()).focus(anyLong(), anyFloat(), anyFloat())
            assertEquals(43, f.scanner.viewId)
        } finally { f.close() }
    }

    @Test
    fun `queued focus cannot cancel a Dart control admitted before the gesture runs`() = runBlocking<Unit> {
        val queued = ArrayDeque<Runnable>()
        var deferDispatch = false
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                if (deferDispatch) queued += block else block.run()
            }
        }
        val f = Fixture(dispatcher)
        try {
            f.activate()
            deferDispatch = true
            f.scanner.focus(1000L, 0F, 0F)
            val zoom = CompletableDeferred<Unit>()
            f.zoom += zoom
            val control = f.calls.async { f.scanner.setZoomRatio(2F) }
            while (queued.isNotEmpty()) queued.removeFirst().run()

            verify(f.camera, never()).focus(anyLong(), anyFloat(), anyFloat())
            assertFalse(zoom.isCancelled)
            assertFalse(control.isCompleted)
            zoom.complete(Unit)
            while (queued.isNotEmpty()) queued.removeFirst().run()
            control.await()
        } finally { f.close() }
    }

    private class Fixture(dispatcher: CoroutineDispatcher = Dispatchers.Unconfined) {
        val camera = mock(Camera::class.java)
        val analyzer = mock(ImageBarcodeAnalyzer::class.java)
        val connection = CameraConnection()
        val host = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this).apply {
                currentState = Lifecycle.State.RESUMED
            }
        }
        val boundOwners = mutableListOf<LifecycleOwner>()
        val handler = mock(Handler::class.java)
        val first = view(42)
        val second = view(43)
        val calls = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val posts = ArrayDeque<Runnable>()
        val timers = mutableListOf<Runnable>()
        val delays = mutableListOf<Long>()
        val zoom = ArrayDeque<Deferred<Unit>>()
        val results = mutableListOf<Pair<Int, Barcode>>()
        val released = mutableListOf<Throwable>()
        lateinit var onFrame: OnCameraFrame
        lateinit var initialized: () -> Unit
        lateinit var availability: (CameraAvailability) -> Unit
        lateinit var failed: (Exception) -> Unit
        lateinit var previewReady: () -> Unit
        val scanner: Scanner

        init {
            doAnswer { posts += it.getArgument<Runnable>(0); true }.`when`(handler).post(anyValue())
            doAnswer {
                timers += it.getArgument<Runnable>(0); delays += it.getArgument<Long>(1); true
            }.`when`(handler).postDelayed(anyValue(), anyLong())
            doAnswer { timers.remove(it.getArgument<Runnable>(0)); null }.`when`(handler).removeCallbacks(anyValue())
            doReturn(CompletableDeferred(Unit)).`when`(camera).resetFocus()
            doReturn(CompletableDeferred(Unit)).`when`(camera).setTorch(anyBoolean())
            doAnswer { zoom.removeFirstOrNull() ?: CompletableDeferred(Unit) }.`when`(camera).setZoomRatio(anyFloat())
            doAnswer {
                boundOwners += it.getArgument<LifecycleOwner>(0)
                onFrame = it.getArgument(2); availability = it.getArgument(3); initialized = it.getArgument(4); failed = it.getArgument(5)
                null
            }.`when`(camera).bind(anyValue(), anyValue(), anyValue(), anyValue(), anyValue(), anyValue())
            doReturn(BARCODE).`when`(analyzer).analyze(anyValue(), anyValue())

            scanner = Scanner(camera, analyzer, handler, { _, cause -> released += cause },
                { id, barcode -> results += id to barcode }, CoroutineScope(SupervisorJob() + dispatcher),
                connection)
            scanner.attachActivity(host.lifecycle)
        }

        fun capture(): Deferred<Unit> {
            scanner.select(first)
            return calls.async { scanner.capture(ScannerConfiguration()) }
        }

        suspend fun activate() {
            val capture = capture()
            open()
            capture.await()
        }

        fun emitFrame() {
            val frame = mock(CameraFrame::class.java)
            doReturn(Rect(0, 0, 100, 100)).`when`(frame).cropRect
            onFrame(frame)
        }
        fun open() { initialized(); availability(CameraAvailability.Open) }
        fun drain() { while (posts.isNotEmpty()) posts.removeFirst().run() }
        fun close() { scanner.dispose(); calls.cancel() }

        private fun view(id: Int) = mock(ScannerConsumer::class.java).also {
            doReturn(id).`when`(it).viewId
            doReturn(mock(android.util.Size::class.java)).`when`(it).size
        }
    }

    private companion object {
        val BARCODE = Barcode("value", "value", 1, 1)
        fun <T> anyValue(): T = any<T>()
    }
}
// endregion

// region ScannerExpiryTest
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
internal class ScannerExpiryTest {
    @Test fun `release preserves resources beyond the former native grace period`() {
        val camera = mock(Camera::class.java)
        val analyzer = mock(ImageBarcodeAnalyzer::class.java)
        val scanner = Scanner(camera, analyzer, Handler(Looper.getMainLooper()), { _, _ -> })
        scanner.select(ScannerConsumer(42))
        scanner.releaseCamera()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertFalse(scanner.isDisposed)
        verify(camera, never()).unbind()
        verify(camera, never()).dispose()
        scanner.dispose()
        verify(camera).dispose()
        verify(analyzer).dispose()
    }
    @Test fun `manual pause unbinds immediately without destroying the texture owner`() {
        val camera = mock(Camera::class.java)
        val scanner = Scanner(camera, mock(ImageBarcodeAnalyzer::class.java), Handler(Looper.getMainLooper()), { _, _ -> })
        scanner.select(ScannerConsumer(42))
        scanner.pauseCamera()
        verify(camera).unbind()
        verify(camera, never()).dispose()
        assertNull(scanner.viewId)
        scanner.dispose()
    }
}
// endregion

// region ScannerLifetimeTest
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

        assertTrue(runCatching { f.startCamera() }.exceptionOrNull() is IllegalStateException)
        assertSame(PluginError.CameraSessionDisposed,
            runCatching { f.scanner.subscribeToScanResults {} }.exceptionOrNull())
        assertEquals(1, f.bindCount)
    }

    @Test
    fun `released scanner cannot resume analysis of already queued frames`() = withScanner { f ->
        f.scanner.dispose()
        f.scanner.startScan(0)
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
            f.scanner.startScan(0)
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
        assertEquals(1, f.bindCount)
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
        val view = mock(ScannerConsumer::class.java)
        lateinit var scanner: Scanner
        lateinit var executor: ExecutorService
        lateinit var onFrame: OnCameraFrame
        var bindCount = 0

        init {
            doReturn(kotlinx.coroutines.CompletableDeferred(Unit)).`when`(camera).resetFocus()
            doReturn(kotlinx.coroutines.CompletableDeferred(Unit)).`when`(camera).setZoomRatio(org.mockito.ArgumentMatchers.anyFloat())
            doReturn(kotlinx.coroutines.CompletableDeferred(Unit)).`when`(camera).setTorch(org.mockito.ArgumentMatchers.anyBoolean())
            scanner = scannerForTest(camera, analyzer, view)
            doReturn(BOUNDS).`when`(frame).cropRect
            doReturn(BARCODE).`when`(analyzer).analyze(frame, BOUNDS)
            doAnswer {
                bindCount++
                executor = it.getArgument(1)
                onFrame = it.getArgument(2)
                it.getArgument<() -> Unit>(4)()
                it.getArgument<com.dns_technologies.mlkit_scanner.scanner.components.camera.OnCameraAvailabilityChanged>(3)(
                    com.dns_technologies.mlkit_scanner.scanner.components.camera.CameraAvailability.Open)
                null
            }.`when`(camera).bind(anyValue(), anyValue(), anyValue(), anyValue(), anyValue(), anyValue())
            startCamera()
            scanner.startScan(0)
        }

        fun startCamera() { scanner.select(view); scanner.captureForTest() }
        fun emitFrame() = onFrame(frame)
    }

    private companion object {
        val BOUNDS = Rect(0, 0, 100, 100)
        val BARCODE = Barcode("value", "value", 1, 1)
        fun <T> anyValue(): T = any<T>()
    }
}
// endregion

// region ScannerOwnershipTest
internal class ScannerOwnershipTest {
    @Test fun `scanner owns preview publications and rejects them after disposal`() {
        val camera = mock(Camera::class.java)
        val analyzer = mock(ImageBarcodeAnalyzer::class.java)
        val disposal = CompletableDeferred<Unit>()
        doReturn(disposal).`when`(camera).disposal
        lateinit var publish: (Map<String, Any>?) -> Unit
        doAnswer { publish = it.getArgument(0); null }.`when`(camera).onPreviewChanged = anyValue()
        val descriptions = mutableListOf<Map<String, Any>?>()
        val scanner = Scanner(camera, analyzer, mock(Handler::class.java), { _, _ -> },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            onPreviewChanged = { _, description -> descriptions += description })

        val preview = mapOf<String, Any>("textureId" to 7)
        publish(preview)
        scanner.dispose()
        publish(mapOf("textureId" to 9))

        assertEquals(listOf(preview), descriptions)
        verify(camera).dispose()
        verify(analyzer).dispose()
        assertFalse(scanner.disposal.isCompleted)
        disposal.complete(Unit)
        assertTrue(scanner.disposal.isCompleted)
    }

    @Test fun `scanner factory owns cleanup when analyzer creation fails`() {
        val camera = mock(Camera::class.java)
        val failure = IllegalStateException("Cannot create analyzer")
        val cleanup = IllegalStateException("Camera cleanup failed")
        doThrow(cleanup).`when`(camera).dispose()

        val error = runCatching {
            Scanner.create({ camera }, { throw failure }, mock(Handler::class.java),
                { _, _ -> }, { _, _ -> }, { _, _ -> })
        }.exceptionOrNull()

        assertSame(failure, error)
        assertEquals(listOf(cleanup), failure.suppressed.toList())
        verify(camera).dispose()
    }

    private fun <T> anyValue(): T = any<T>()
}
// endregion
