package com.dns_technologies.mlkit_scanner.scanner

import android.view.View
import androidx.lifecycle.LifecycleOwner
import com.dns_technologies.mlkit_scanner.scanner.components.analyzer.ImageBarcodeAnalyzer
import com.dns_technologies.mlkit_scanner.scanner.components.camera.Camera
import com.dns_technologies.mlkit_scanner.scanner.components.camera.CameraCommand
import com.dns_technologies.mlkit_scanner.scanner.components.camera.CameraFrame
import com.dns_technologies.mlkit_scanner.scanner.components.camera.OnCameraAvailabilityChanged
import com.dns_technologies.mlkit_scanner.scanner.components.camera.OnError
import com.dns_technologies.mlkit_scanner.scanner.components.camera.OnInit
import com.dns_technologies.mlkit_scanner.scanner.models.Barcode
import com.dns_technologies.mlkit_scanner.scanner.models.RecognizeVisorCropRect
import com.dns_technologies.mlkit_scanner.scanner.models.ScanResultSubscription
import com.dns_technologies.mlkit_scanner.scanner.utils.ScanAreaState
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.Job

/** Listener that receives decoded scanner results. */
typealias OnScanResultListener = (result: Barcode) -> Unit

/**
 * Owns scanner behavior independent from Flutter platform view plumbing.
 *
 * @property camera Camera adapter used for preview, focus, flash and zoomRatio.
 * @property analyzer Barcode analyzer used for throttled frame recognition.
 */
class Scanner(
    private val camera: Camera,
    private val analyzer: ImageBarcodeAnalyzer,
) {
    private var analysisExecutor: ExecutorService? = null
    private val scanJobLock = Any()
    private var scanJob: CompletableJob? = null
    private val scanAreaState = ScanAreaState()
    @Volatile
    private var cropArea: RecognizeVisorCropRect? = null
    private val scanResultListeners = CopyOnWriteArraySet<OnScanResultListener>()

    /** Indicates whether incoming frames should be sent to the analyzer. */
    val isScanActive: Boolean
        get() = synchronized(scanJobLock) { scanJob?.isActive == true }

    /** Native preview view supplied by the camera adapter. */
    val previewView: View
        get() = camera.previewView

    /** Starts the delegated camera and wires common frame handling. */
    fun startCamera(
        lifecycleOwner: LifecycleOwner,
        onAvailabilityChanged: OnCameraAvailabilityChanged,
        onInit: OnInit,
        onError: OnError,
    ) {
        val executor = analysisExecutor
            ?.takeUnless { it.isShutdown }
            ?: Executors.newSingleThreadExecutor().also { analysisExecutor = it }

        camera.bind(
            lifecycleOwner = lifecycleOwner,
            analysisExecutor = executor,
            onFrame = this::analyzeFrame,
            onAvailabilityChanged = onAvailabilityChanged,
            onInit = onInit,
            onError = onError,
        )
    }

    /** Compatibility overload for scanner-only callers that do not coordinate CameraX state. */
    fun startCamera(
        lifecycleOwner: LifecycleOwner,
        onInit: OnInit,
        onError: OnError,
    ) = startCamera(lifecycleOwner, {}, onInit, onError)

    /** Returns true when the scanner camera is active. */
    fun isActive(): Boolean = camera.isBound()

    /** Executes one stateless camera command selected by the owning scanner session. */
    fun executeCameraCommand(command: CameraCommand) = camera.execute(command)

    /** Applies torch through the stateless camera command boundary. */
    fun setTorch(enabled: Boolean) = executeCameraCommand(CameraCommand.SetTorch(enabled))

    /** Starts focus through the stateless camera command boundary. */
    fun focusOnCenter(resetDelayMs: Long, offsetX: Float, offsetY: Float) =
        executeCameraCommand(CameraCommand.Focus(resetDelayMs, offsetX, offsetY))

    /** Clears focus through the stateless camera command boundary. */
    fun resetFocus() = executeCameraCommand(CameraCommand.ResetFocus)

    /** Applies an absolute zoom ratio through the stateless camera command boundary. */
    fun setZoomRatio(value: Float) = executeCameraCommand(CameraCommand.SetZoomRatio(value))

    /** Starts analysis with the configured analyzer component. */
    fun startScan(periodMs: Int) {
        analyzer.updatePeriod(periodMs)
        resumeScan()
    }

    /** Resumes analysis with the period already retained by the analyzer. */
    fun resumeScan() {
        synchronized(scanJobLock) {
            if (scanJob?.isActive == true) return
            scanJob = Job()
        }
    }

    /** Pauses frame analysis without releasing analyzer resources. */
    fun pauseScan() {
        synchronized(scanJobLock) {
            scanJob?.cancel()
            scanJob = null
        }
    }

    /** Updates the analyzer cooldown applied after successful recognition. */
    fun updateScanPeriod(periodMs: Int) {
        analyzer.updatePeriod(periodMs)
    }

    /** Subscribes to decoded scanner results and returns a cancellable subscription. */
    fun subscribeToScanResults(listener: OnScanResultListener): ScanResultSubscription {
        scanResultListeners += listener
        return ScanResultSubscription { scanResultListeners -= listener }
    }

    /** Updates scanner crop settings used for frame preparation. */
    fun setCropArea(cropRect: RecognizeVisorCropRect?) {
        cropArea = cropRect
    }

    /** Reveals camera preview after startup controls have been applied. */
    fun showPreview() = camera.showPreview()

    /** Preserves the last camera frame until active-view configuration is restored. */
    fun hidePreview() = camera.hidePreview()

    /** Releases scanner components and stops pending analysis work. */
    fun dispose() {
        pauseScan()
        camera.dispose()
        analysisExecutor?.shutdownNow()
        analysisExecutor = null
        analyzer.dispose()
        scanResultListeners.clear()
    }

    /** Processes a camera frame when scanning is active. */
    private fun analyzeFrame(frame: CameraFrame) {
        val analysisJob = createAnalysisJob() ?: return

        try {
            val cropRect = scanAreaState.resolve(frame, cropArea)
            if (cropRect.isEmpty) return
            val result = analyzer.analyze(frame, cropRect) ?: return
            synchronized(scanJobLock) {
                if (analysisJob.isActive) emitScanResult(result)
            }
        } finally {
            analysisJob.complete()
        }
    }

    /** Creates one child job owned by the currently active scan run. */
    private fun createAnalysisJob(): CompletableJob? = synchronized(scanJobLock) {
        val activeScanJob = scanJob?.takeIf { it.isActive } ?: return@synchronized null
        Job(activeScanJob)
    }

    /** Notifies all active listeners about a recognized scanner result. */
    private fun emitScanResult(result: Barcode) {
        scanResultListeners.forEach { listener ->
            listener.invoke(result)
        }
    }
}
