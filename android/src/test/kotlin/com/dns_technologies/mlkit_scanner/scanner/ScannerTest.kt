package com.dns_technologies.mlkit_scanner.scanner

import android.view.View
import androidx.lifecycle.LifecycleOwner
import com.dns_technologies.mlkit_scanner.scanner.components.analyzer.ImageBarcodeAnalyzer
import com.dns_technologies.mlkit_scanner.scanner.components.camera.Camera
import com.dns_technologies.mlkit_scanner.scanner.components.camera.CameraFrame
import com.dns_technologies.mlkit_scanner.scanner.components.camera.OnCameraFrame
import com.dns_technologies.mlkit_scanner.scanner.components.camera.OnError
import com.dns_technologies.mlkit_scanner.scanner.components.camera.OnInit
import com.dns_technologies.mlkit_scanner.scanner.components.camera.Rect
import com.dns_technologies.mlkit_scanner.scanner.models.Barcode
import com.dns_technologies.mlkit_scanner.scanner.models.RecognizeVisorCropRect
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.mock
import java.util.concurrent.ExecutorService

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
    fun `analyzer controls lazy frame materialization`() {
        val fixture = Fixture()
        fixture.scanner.startCamera(mock(LifecycleOwner::class.java), {}, {})
        fixture.scanner.startScan(periodMs = 100)

        fixture.emitFrame()
        fixture.emitFrame()

        assertEquals(1, fixture.materializedFrames)
        assertEquals(2, fixture.closedFrames)
        assertEquals(1, fixture.analyzer.acceptedAnalysisCalls)
    }

    @Test
    fun `scanner passes calculated crop to analyzer`() {
        val fixture = Fixture()
        fixture.scanner.startCamera(mock(LifecycleOwner::class.java), {}, {})
        fixture.scanner.setCropArea(
            RecognizeVisorCropRect(scaleWidth = 0.5, scaleHeight = 0.5),
        )
        fixture.scanner.startScan(periodMs = 100)

        fixture.emitFrame()

        assertEquals(Rect(180, 256, 540, 1024), fixture.lastCropRect)
    }

    @Test
    fun `start scan updates analyzer period`() {
        val fixture = Fixture()
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

    private class Fixture {
        private var currentTimeMs = 0L
        val camera = FakeCamera()
        val analyzer = FakeAnalyzer { currentTimeMs }
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
    ) : ImageBarcodeAnalyzer(currentTimeMs) {
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
            return null
        }

        override fun disposeAnalyzer() = Unit
    }

    private class FakeCamera : Camera {
        override val previewView: View = mock(View::class.java)
        private var onFrame: OnCameraFrame? = null

        override fun start(
            lifecycleOwner: LifecycleOwner,
            analysisExecutor: ExecutorService,
            onFrame: OnCameraFrame,
            onInit: OnInit,
            onError: OnError,
        ) {
            this.onFrame = onFrame
            onInit()
        }

        fun emitFrame(frame: CameraFrame) {
            frame.use { onFrame?.invoke(it) }
        }

        override fun isActive(): Boolean = onFrame != null

        override fun toggleFlashLight() = Unit

        override fun focusOnCenter(resetDelayMs: Long, offsetX: Float, offsetY: Float) = Unit

        override fun setZoom(value: Float) = Unit

        override fun dispose() {
            onFrame = null
        }
    }
}
