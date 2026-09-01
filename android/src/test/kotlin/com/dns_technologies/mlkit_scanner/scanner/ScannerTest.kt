package com.dns_technologies.mlkit_scanner.scanner

import android.view.View
import androidx.lifecycle.LifecycleOwner
import com.dns_technologies.mlkit_scanner.PluginError
import com.dns_technologies.mlkit_scanner.scanner.components.analyzer.ImageBarcodeAnalyzer
import com.dns_technologies.mlkit_scanner.scanner.components.camera.Camera
import com.dns_technologies.mlkit_scanner.scanner.components.camera.CameraCommand
import com.dns_technologies.mlkit_scanner.scanner.components.camera.CameraFrame
import com.dns_technologies.mlkit_scanner.scanner.components.camera.OnCameraAvailabilityChanged
import com.dns_technologies.mlkit_scanner.scanner.components.camera.OnCameraFrame
import com.dns_technologies.mlkit_scanner.scanner.components.camera.OnError
import com.dns_technologies.mlkit_scanner.scanner.components.camera.OnInit
import com.dns_technologies.mlkit_scanner.scanner.components.camera.Rect
import com.dns_technologies.mlkit_scanner.scanner.models.Barcode
import com.dns_technologies.mlkit_scanner.scanner.models.RecognizeVisorCropRect
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal class ScannerTest {
    @Test
    fun `paused scanner does not send camera frame to analyzer`() {
        val fixture = Fixture()
        fixture.scanner.startCamera(mock(LifecycleOwner::class.java), {}, {})
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
        fixture.scanner.startCamera(mock(LifecycleOwner::class.java), {}, {})
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
        fixture.scanner.startCamera(mock(LifecycleOwner::class.java), {}, {})
        fixture.scanner.setCropArea(cropArea)
        fixture.scanner.startScan(periodMs = 100)

        fixture.emitFrame()

        assertEquals(Rect(180, 320, 540, 960), fixture.lastCropRect)
    }

    @Test
    fun `scanner analyzes only the CameraX preview crop when custom crop is absent`() {
        val fixture = Fixture(frameCropRect = Rect(10, 20, 710, 1260))
        fixture.scanner.startCamera(mock(LifecycleOwner::class.java), {}, {})
        fixture.scanner.startScan(periodMs = 0)

        fixture.emitFrame()

        assertEquals(Rect(10, 20, 710, 1260), fixture.lastCropRect)
    }

    @Test
    fun `scanner safely aligns odd CameraX preview crop when custom crop is absent`() {
        val fixture = Fixture(frameCropRect = Rect(11, 21, 709, 1259))
        fixture.scanner.startCamera(mock(LifecycleOwner::class.java), {}, {})
        fixture.scanner.startScan(periodMs = 0)

        fixture.emitFrame()

        assertEquals(Rect(12, 22, 708, 1258), fixture.lastCropRect)
    }

    @Test
    fun `scanner skips analyzer when crop is outside preview`() {
        val fixture = Fixture()
        fixture.scanner.startCamera(mock(LifecycleOwner::class.java), {}, {})
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
    fun `runtime zoom before initialization is rejected by camera adapter`() {
        val fixture = Fixture()

        val error = runCatching { fixture.scanner.setZoom(0.75F) }.exceptionOrNull()

        assertSame(PluginError.CameraIsNotInitialized, error)
        assertEquals(emptyList<Float>(), fixture.camera.zoomValues)
    }

    @Test
    fun `zoom is applied through the camera adapter`() = runBlocking {
        val fixture = Fixture()

        fixture.scanner.startCamera(
            lifecycleOwner = mock(LifecycleOwner::class.java),
            onInit = {},
            onError = {},
        )
        fixture.scanner.setZoom(0.75F).await()

        assertEquals(listOf(0.75F), fixture.camera.zoomValues)
    }

    @Test
    fun `resume scan preserves successful recognition cooldown`() {
        val fixture = Fixture(analysisResult = BARCODE)
        fixture.scanner.startCamera(mock(LifecycleOwner::class.java), {}, {})
        fixture.scanner.startScan(periodMs = 250)
        fixture.emitFrame()
        fixture.scanner.pauseScan()
        fixture.setCurrentTimeMs(249)

        fixture.scanner.resumeScan()
        fixture.emitFrame()

        assertEquals(1, fixture.analyzer.acceptedAnalysisCalls)

        fixture.setCurrentTimeMs(250)

        fixture.emitFrame()

        assertEquals(2, fixture.analyzer.acceptedAnalysisCalls)
    }

    @Test
    fun `start scan updates analyzer period`() {
        val fixture = Fixture(analysisResult = BARCODE)
        fixture.scanner.startCamera(mock(LifecycleOwner::class.java), {}, {})

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
        val scanner = Scanner(camera, analyzer)
        val received = mutableListOf<Barcode>()
        scanner.subscribeToScanResults(received::add)
        scanner.startCamera(mock(LifecycleOwner::class.java), {}, {})
        scanner.startScan(periodMs = 0)
        val executor = Executors.newSingleThreadExecutor()

        try {
            val analysis = executor.submit { camera.emitFrame(testFrame()) }
            assertTrue(analysisStarted.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS))

            scanner.pauseScan()
            scanner.resumeScan()
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
        val scanner = Scanner(camera, ResultAnalyzer())
        val received = mutableListOf<Barcode>()
        scanner.subscribeToScanResults(received::add)
        scanner.startCamera(mock(LifecycleOwner::class.java), {}, {})
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
        val scanner = Scanner(camera, analyzer)
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
        override val previewView: View = mock(View::class.java)
        val zoomValues = mutableListOf<Float>()
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
        }

        fun emitFrame(frame: CameraFrame) {
            frame.use { onFrame?.invoke(it) }
        }

        override fun isBound(): Boolean = onFrame != null

        override fun execute(command: CameraCommand): Deferred<Unit> {
            if (!isBound()) throw PluginError.CameraIsNotInitialized
            if (command is CameraCommand.SetZoom) zoomValues += command.value
            return CompletableDeferred(Unit)
        }

        override fun showPreview() = Unit

        override fun hidePreview() = Unit

        override fun unbind() {
            onFrame = null
        }

        override fun dispose() {
            unbind()
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
