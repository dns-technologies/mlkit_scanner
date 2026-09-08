package com.dns_technologies.mlkit_scanner.scanner

import android.os.Handler
import android.util.Log
import androidx.annotation.MainThread
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.dns_technologies.mlkit_scanner.CameraControlOperation
import com.dns_technologies.mlkit_scanner.PluginConstants
import com.dns_technologies.mlkit_scanner.PluginError
import com.dns_technologies.mlkit_scanner.scanner.components.analyzer.ImageBarcodeAnalyzer
import com.dns_technologies.mlkit_scanner.scanner.components.camera.Camera
import com.dns_technologies.mlkit_scanner.scanner.components.camera.CameraConnection
import com.dns_technologies.mlkit_scanner.scanner.components.camera.CameraFrame
import com.dns_technologies.mlkit_scanner.scanner.models.Barcode
import com.dns_technologies.mlkit_scanner.scanner.models.RecognizeVisorCropRect
import com.dns_technologies.mlkit_scanner.scanner.utils.ScanAreaState
import com.dns_technologies.mlkit_scanner.utils.ExceptionCollector
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus

/** Native scanner operations. Dart owns selection, serialization and retained configuration. */
@MainThread
internal class Scanner(
    private val camera: Camera,
    private val analyzer: ImageBarcodeAnalyzer,
    private val mainHandler: Handler,
    private val onReleased: (Scanner, Throwable) -> Unit,
    private val onResult: (Int, Barcode) -> Unit = { _, _ -> },
    val scope: CoroutineScope = createScope(),
    private val connection: CameraConnection = CameraConnection(),
) {
    /** Borrows only the selected view; Flutter owns its lifetime. */
    private var view: ScannerView? = null
    val viewId: Int? get() = view?.viewId
    private var analysisExecutor: ExecutorService? = null
    private val scanJobLock = Any()
    private var scanJob: CompletableJob? = null
    private val scanAreaState = ScanAreaState()
    @Volatile
    private var cropArea: RecognizeVisorCropRect? = null
    private val scanResultListeners = linkedSetOf<OnScanResultListener>()

    @Volatile
    private var closeCause: Throwable? = null
    private var operation: Deferred<Unit>? = null
    /** Only transient focus may be replaced by another gesture. */
    private var operationIsFocus = false
    private var scan: Scan? = null
    /** Borrows the Activity's real lifecycle without mirroring its state or observing events. */
    private var lifecycleOwner: LifecycleOwner? = null
    val isDisposed: Boolean get() = closeCause != null
    private val idleDisposal = Runnable {
        try {
            dispose()
        } catch (error: Exception) {
            Log.w(PluginConstants.LOG_TAG, "Idle scanner cleanup failed", error)
        }
    }

    /** Selection precedes permission await so release can interrupt an unfinished capture. */
    fun select(target: ScannerView) {
        check(!isDisposed) { "Scanner is disposed" }
        if (target.isDisposed) return
        mainHandler.removeCallbacks(idleDisposal)
        cancelOperation()
        pauseScan()
        view?.detachPreview()
        view = target
        target.attachPreview(camera.previewView) { scan?.resumeIfReady() }
        camera.hidePreview()
    }

    /** Applies one Dart snapshot, retaining it only for the duration of this operation. */
    suspend fun capture(configuration: ScannerConfiguration, permission: suspend () -> Boolean) = runOperation { id ->
        view?.setCropArea(configuration.cropArea ?: RecognizeVisorCropRect())
        if (!permission()) throw PluginError.AuthorizationCameraError
        currentCoroutineContext().ensureActive()
        connection.awaitReady(::bindCamera)
        connection.control(id, CameraControlOperation.FOCUS, camera::resetFocus)
        cropArea = configuration.cropArea
        connection.control(id, CameraControlOperation.ZOOM) { camera.setZoomRatio(configuration.zoomRatio) }
        connection.control(id, CameraControlOperation.TORCH) { camera.setTorch(configuration.torchEnabled) }
        camera.showPreview()
        view?.bindFocus()
        currentCoroutineContext().ensureActive()
        if (configuration.scanEnabled) startScan(configuration.scanDelay)
    }

    suspend fun setZoomRatio(value: Float) = runOperation { id ->
        connection.control(id, CameraControlOperation.ZOOM) { camera.setZoomRatio(value) }
    }

    suspend fun setTorch(enabled: Boolean) = runOperation { id ->
        connection.control(id, CameraControlOperation.TORCH) { camera.setTorch(enabled) }
    }

    /** A new gesture replaces transient focus, but cannot interrupt an unfinished Dart command. */
    fun focus(resetDelayMs: Long, offsetX: Float, offsetY: Float) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            if (operation != null && !operationIsFocus || !connection.isReady) return@launch
            try {
                runOperation(isFocus = true) { id ->
                    connection.control(id, CameraControlOperation.FOCUS) {
                        val preview = camera.previewView
                        camera.focus(resetDelayMs, preview.width / 2F + offsetX, preview.height / 2F + offsetY)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(PluginConstants.LOG_TAG, "Camera focus failed", error)
            }
        }
    }

    fun startScan(periodMs: Int) {
        val id = viewId ?: return
        setScanPeriod(periodMs)
        if (scan == null) {
            scan = Scan(id)
        }
        scan?.resumeIfReady()
    }

    fun pauseScan() {
        val previous = scan
        scan = null
        val failures = ExceptionCollector()
        failures.attempt { previous?.cancel() }
        failures.attempt(this::pauseAnalysis)
        failures.attempt { view?.setScanActive(false) }
        failures.throwIfFailed()
    }

    fun setCropArea(crop: RecognizeVisorCropRect) {
        view?.setCropArea(crop)
        cropArea = crop
    }

    /** Detaches immediately; a new consumer has 300 ms to reuse the SDK resources. */
    fun releaseCamera() {
        if (viewId == null) return
        val previous = view
        view = null
        mainHandler.postDelayed(idleDisposal, 300L)
        val failures = ExceptionCollector()
        failures.attempt(::cancelOperation)
        failures.attempt(::pauseScan)
        failures.attempt { previous?.detachPreview() }
        failures.attempt(camera::hidePreview)
        failures.throwIfFailed()
    }

    fun attachActivity(lifecycle: Lifecycle) {
        if (isDisposed || lifecycleOwner?.lifecycle === lifecycle) return
        if (lifecycleOwner != null) detachActivity()
        lifecycleOwner = object : LifecycleOwner {
            override val lifecycle: Lifecycle = lifecycle
        }
    }

    /** Drops the old Activity binding; Dart's next capture supplies settings for its replacement. */
    fun detachActivity() {
        if (isDisposed) return
        lifecycleOwner = null
        val failures = ExceptionCollector()
        failures.attempt(::releaseCamera)
        failures.attempt(connection::reset)
        failures.attempt(camera::unbind)
        failures.throwIfFailed()
    }

    /** Terminal cleanup attempts every resource once, including after a failing SDK callback. */
    fun dispose(cause: Throwable = PluginError.CameraSessionDisposed) {
        if (isDisposed) return
        mainHandler.removeCallbacks(idleDisposal)
        val executor = synchronized(scanJobLock) {
            closeCause = cause
            pauseAnalysis()
            scanResultListeners.clear()
            analysisExecutor.also { analysisExecutor = null }
        }
        val previous = view
        view = null
        lifecycleOwner = null
        val failures = ExceptionCollector()
        failures.attempt(::cancelOperation)
        failures.attempt(::pauseScan)
        failures.attempt { previous?.detachPreview() }
        failures.attempt(camera::dispose)
        failures.attempt { executor?.shutdownNow() }
        failures.attempt(analyzer::dispose)
        failures.attempt { connection.dispose(cause) }
        failures.attempt { scope.cancel() }
        failures.attempt { onReleased(this, cause) }
        failures.throwIfFailed()
    }

    private fun bindCamera(onInit: () -> Unit) {
        val owner = lifecycleOwner ?: throw PluginError.CameraSessionDisposed
        try {
            camera.bind(owner, analysisExecutor(), this::analyzeFrame,
                onAvailabilityChanged = { availability -> post {
                    if (lifecycleOwner !== owner) return@post
                    connection.onAvailabilityChanged(availability, viewId)
                    scan?.resumeIfReady()
                } },
                onInit = { post { if (lifecycleOwner === owner) onInit() } },
                onError = { error -> post { if (lifecycleOwner === owner) dispose(error) } },
            )
        } catch (error: Exception) {
            try { dispose(error) } catch (cleanup: Exception) {
                if (cleanup !== error) error.addSuppressed(cleanup)
            }
            throw error
        }
    }

    /** Owns only the in-flight SDK job; there is no native command queue or saved view state. */
    private suspend fun runOperation(isFocus: Boolean = false, action: suspend (Int) -> Unit) {
        val id = viewId ?: return
        val work = scope.async(start = CoroutineStart.LAZY) { action(id) }
        val previous = operation
        operation = work
        operationIsFocus = isFocus
        try {
            previous?.cancel()
            work.start()
            work.await()
        } catch (error: CancellationException) {
            closeCause?.let { throw it }
            if (operation === work) throw error
        } finally {
            if (operation === work) operation = null
            work.cancel()
        }
    }

    private fun cancelOperation() {
        val previous = operation
        operation = null
        previous?.cancel()
    }

    private fun post(action: () -> Unit) {
        scope.launch { if (!isDisposed) action() }
    }

    /** Subscription lifetime rejects queued results even across release/recapture of the same ID. */
    private inner class Scan(private val id: Int) {
        private val deliveries = CoroutineScope(scope.coroutineContext +
            SupervisorJob(scope.coroutineContext[Job]) + mainHandler.asCoroutineDispatcher())
        private val subscription = subscribeToScanResults { barcode ->
            deliveries.launch {
                if (scan === this@Scan && viewId == id && connection.isReady) onResult(id, barcode)
            }
        }
        fun resumeIfReady() {
            if (scan === this && viewId == id && view?.isPreviewReady() == true && connection.isReady) {
                resumeAnalysis()
                view?.setScanActive(true)
            } else {
                // Closing and reopening must not revive results from the previous camera stream.
                pauseAnalysis()
                deliveries.coroutineContext.cancelChildren()
                view?.setScanActive(false)
            }
        }
        fun cancel() {
            try { deliveries.cancel() } finally { subscription.cancel() }
        }
    }

    private fun analysisExecutor(): ExecutorService = synchronized(scanJobLock) {
        if (isDisposed) throw PluginError.CameraSessionDisposed
        analysisExecutor ?: Executors.newSingleThreadExecutor().also { analysisExecutor = it }
    }

    /** Resumes analysis with the period already retained by the analyzer. */
    private fun resumeAnalysis() {
        synchronized(scanJobLock) {
            if (isDisposed || scanJob?.isActive == true) return
            scanJob = Job()
        }
    }

    /** Pauses frame analysis without releasing analyzer resources. */
    private fun pauseAnalysis() {
        synchronized(scanJobLock) {
            scanJob?.cancel()
            scanJob = null
        }
    }

    /** Updates the analyzer cooldown applied after successful recognition. */
    fun setScanPeriod(periodMs: Int) {
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

    internal companion object {

        fun createScope(): CoroutineScope = MainScope() + CoroutineExceptionHandler { _, error ->
            Log.e(PluginConstants.LOG_TAG, "Scanner failed", error)
        }
    }
}

/** Receives decoded results synchronously on the analysis thread. */
typealias OnScanResultListener = (result: Barcode) -> Unit

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
