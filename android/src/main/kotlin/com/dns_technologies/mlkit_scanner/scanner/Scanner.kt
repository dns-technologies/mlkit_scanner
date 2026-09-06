package com.dns_technologies.mlkit_scanner.scanner

import android.view.View
import androidx.annotation.MainThread
import androidx.lifecycle.LifecycleOwner
import com.dns_technologies.mlkit_scanner.PluginError
import com.dns_technologies.mlkit_scanner.scanner.components.analyzer.ImageBarcodeAnalyzer
import com.dns_technologies.mlkit_scanner.scanner.components.camera.Camera
import com.dns_technologies.mlkit_scanner.scanner.components.camera.CameraFrame
import com.dns_technologies.mlkit_scanner.scanner.components.camera.OnCameraAvailabilityChanged
import com.dns_technologies.mlkit_scanner.scanner.components.camera.OnError
import com.dns_technologies.mlkit_scanner.scanner.components.camera.OnInit
import com.dns_technologies.mlkit_scanner.scanner.models.Barcode
import com.dns_technologies.mlkit_scanner.scanner.models.RecognizeVisorCropRect
import com.dns_technologies.mlkit_scanner.scanner.utils.ScanAreaState
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job

/** Listener that receives decoded scanner results. */
typealias OnScanResultListener = (result: Barcode) -> Unit

/**
 * Owns scanner behavior independent from Flutter platform view plumbing.
 *
 * Camera lifecycle/controls run on the main thread; analysis runs on the owned serial executor.
 * Scan state and subscriptions are synchronized separately from recognition. Result listeners run
 * synchronously on the analysis thread: they must be short and must not wait for another thread
 * to change scan state. Pause/cancellation cannot interrupt recognition already reading a frame.
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
    private var isDisposed = false
    private var scanJob: CompletableJob? = null
    private val scanAreaState = ScanAreaState()
    @Volatile
    private var cropArea: RecognizeVisorCropRect? = null
    private val scanResultListeners = linkedSetOf<OnScanResultListener>()

    /** Native preview view supplied by the camera adapter. */
    val previewView: View
        get() = camera.previewView

    /** Starts the delegated camera and wires common frame handling. */
    @MainThread
    fun startCamera(
        lifecycleOwner: LifecycleOwner,
        onAvailabilityChanged: OnCameraAvailabilityChanged,
        onInit: OnInit,
        onError: OnError,
    ) {
        val executor = synchronized(scanJobLock) {
            if (isDisposed) throw PluginError.CameraSessionDisposed
            analysisExecutor ?: Executors.newSingleThreadExecutor().also { analysisExecutor = it }
        }

        camera.bind(
            lifecycleOwner = lifecycleOwner,
            analysisExecutor = executor,
            onFrame = this::analyzeFrame,
            onAvailabilityChanged = onAvailabilityChanged,
            onInit = onInit,
            onError = onError,
        )
    }

    /** Starts the camera without subscribing to availability changes. */
    fun startCamera(
        lifecycleOwner: LifecycleOwner,
        onInit: OnInit,
        onError: OnError,
    ) = startCamera(lifecycleOwner, {}, onInit, onError)

    /** Returns true when the scanner camera is active. */
    fun isActive(): Boolean = camera.isBound()

    /** Applies an absolute torch state to the current camera. */
    fun setTorch(enabled: Boolean) = camera.setTorch(enabled)

    /** Starts focus at pixel offsets from the preview center. */
    fun focusOnCenter(resetDelayMs: Long, offsetX: Float, offsetY: Float): Deferred<Unit> {
        val preview = camera.previewView
        return camera.focus(
            resetDelayMs = resetDelayMs,
            x = preview.width / 2F + offsetX,
            y = preview.height / 2F + offsetY,
        )
    }

    /** Clears the current camera's focus and metering regions. */
    fun resetFocus() = camera.resetFocus()

    /** Applies an absolute zoom ratio to the current camera. */
    fun setZoomRatio(value: Float) = camera.setZoomRatio(value)

    /** Starts analysis with the configured analyzer component. */
    fun startScan(periodMs: Int) {
        updateScanPeriod(periodMs)
        resumeScan()
    }

    /** Resumes analysis with the period already retained by the analyzer. */
    fun resumeScan() {
        synchronized(scanJobLock) {
            if (isDisposed || scanJob?.isActive == true) return
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
        synchronized(scanJobLock) {
            if (!isDisposed) analyzer.updatePeriod(periodMs)
        }
    }

    /** Subscribes to decoded results; the same listener is registered at most once. */
    fun subscribeToScanResults(listener: OnScanResultListener): ScanResultSubscription {
        synchronized(scanJobLock) {
            if (isDisposed) throw PluginError.CameraSessionDisposed
            scanResultListeners += listener
        }
        return ScanResultSubscription {
            synchronized(scanJobLock) { scanResultListeners -= listener }
        }
    }

    /** Updates scanner crop settings used for frame preparation. */
    fun setCropArea(cropRect: RecognizeVisorCropRect?) {
        cropArea = cropRect
    }

    /** Reveals camera preview after startup controls have been applied. */
    fun showPreview() = camera.showPreview()

    /** Preserves the last rendered camera frame while controls are being updated. */
    fun hidePreview() = camera.hidePreview()

    /** Invalidates work first, then attempts every owned cleanup once, even if one fails. */
    @MainThread
    fun dispose() {
        val executor = synchronized(scanJobLock) {
            if (isDisposed) return
            isDisposed = true
            pauseScan()
            scanResultListeners.clear()
            analysisExecutor.also { analysisExecutor = null }
        }
        // Never hold the scan lock while releasing SDK resources or interrupting the executor.
        var failure: Exception? = null
        fun release(action: () -> Unit) {
            try {
                action()
            } catch (error: Exception) {
                val first = failure
                if (first == null) failure = error
                else if (first !== error) first.addSuppressed(error)
            }
        }
        release(camera::dispose)
        release { executor?.shutdownNow() }
        release(analyzer::dispose)
        failure?.let { throw it }
    }

    /** Processes a camera frame when scanning is active. */
    private fun analyzeFrame(frame: CameraFrame) {
        val analysisJob = createAnalysisJob() ?: return

        try {
            val cropRect = scanAreaState.resolve(frame, cropArea)
            if (cropRect.isEmpty) return
            val result = analyzer.analyze(frame, cropRect) ?: return
            emitScanResult(result, analysisJob)
        } finally {
            analysisJob.complete()
        }
    }

    /** The child represents this analysis invocation; pausing cancels its result eligibility. */
    private fun createAnalysisJob(): CompletableJob? = synchronized(scanJobLock) {
        val activeScanJob = scanJob?.takeIf { it.isActive } ?: return@synchronized null
        Job(activeScanJob)
    }

    private fun emitScanResult(result: Barcode, analysisJob: Job) = synchronized(scanJobLock) {
        // A listener can synchronously pause/restart scanning or cancel another subscription.
        // Snapshot iteration tolerates those edits; recheck eligibility before each delivery.
        for (listener in scanResultListeners.toList()) {
            if (!analysisJob.isActive) return
            if (listener in scanResultListeners) listener(result)
        }
    }
}

/** Handle used to stop receiving scanner results. */
class ScanResultSubscription internal constructor(
    onCancel: () -> Unit,
) {
    private val cancellation = AtomicReference<(() -> Unit)?>(onCancel)

    /** Stops delivering scan results to the listener associated with this subscription. */
    fun cancel() {
        // Clear ownership before invoking user code, including reentrant or throwing cleanup.
        cancellation.getAndSet(null)?.invoke()
    }
}
