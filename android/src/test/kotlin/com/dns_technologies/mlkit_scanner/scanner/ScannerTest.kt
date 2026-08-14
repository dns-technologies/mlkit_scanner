package com.dns_technologies.mlkit_scanner.scanner

import android.view.View
import androidx.lifecycle.LifecycleOwner
import com.dns_technologies.mlkit_scanner.scanner.components.analyzer.ImageBarcodeAnalyzer
import com.dns_technologies.mlkit_scanner.scanner.components.camera.Camera
import com.dns_technologies.mlkit_scanner.scanner.components.camera.CreateCameraFrame
import com.dns_technologies.mlkit_scanner.scanner.components.camera.OnCameraFrame
import com.dns_technologies.mlkit_scanner.scanner.components.camera.OnError
import com.dns_technologies.mlkit_scanner.scanner.components.camera.OnInit
import com.dns_technologies.mlkit_scanner.scanner.models.Barcode
import com.dns_technologies.mlkit_scanner.scanner.models.images.AnalysingImage
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.mock
import java.util.concurrent.ExecutorService

internal class ScannerTest {
    @Test
    fun `paused scanner does not create camera frame`() {
        val fixture = Fixture()
        fixture.scanner.startCamera(mock(LifecycleOwner::class.java), {}, {})
        fixture.scanner.startScan(periodMs = 100)
        fixture.scanner.pauseScan()

        fixture.emitFrame()

        assertEquals(0, fixture.createdFrames)
        assertEquals(0, fixture.analyzer.analysisCalls)
    }

    @Test
    fun `scanner creates frame only when analyzer accepts it`() {
        val fixture = Fixture()
        fixture.scanner.startCamera(mock(LifecycleOwner::class.java), {}, {})
        fixture.scanner.startScan(periodMs = 100)

        fixture.emitFrame()
        fixture.emitFrame()

        assertEquals(1, fixture.createdFrames)
        assertEquals(1, fixture.analyzer.analysisCalls)
    }

    private class Fixture {
        val camera = FakeCamera()
        val analyzer = FakeAnalyzer()
        val scanner = Scanner(camera, analyzer)
        var createdFrames = 0
            private set

        fun emitFrame() {
            camera.emitFrame {
                createdFrames += 1
                mock(AnalysingImage::class.java)
            }
        }
    }

    private class FakeAnalyzer : ImageBarcodeAnalyzer(currentTimeMs = { 0L }) {
        var analysisCalls = 0
            private set

        override fun analyzeImage(image: AnalysingImage): Barcode? {
            analysisCalls += 1
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

        fun emitFrame(createFrame: CreateCameraFrame) {
            onFrame?.invoke(createFrame)
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
