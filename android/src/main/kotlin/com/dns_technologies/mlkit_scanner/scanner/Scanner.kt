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
    /** Camera adapter owned by this scanner until terminal disposal. */
    private val camera: Camera,
    /** Recognition backend whose resources outlive individual capture leases. */
    private val analyzer: ImageBarcodeAnalyzer,
    /** Dispatcher used to deliver analysis results on the main thread. */
    private val mainHandler: Handler,
    /** Owner callback invoked after terminal cleanup attempts finish. */
    private val onReleased: (Scanner, Throwable) -> Unit,
    /** Delivers recognized values with the currently selected widget identifier. */
    private val onResult: (Int, Barcode) -> Unit = { _, _ -> },
    /** Owns control work and result delivery for this scanner's lifetime. */
    val scope: CoroutineScope = createScope(),
    /** Tracks SDK binding, readiness, and camera-control failures. */
    private val connection: CameraConnection = CameraConnection(),
    /** Forwards preview state with this scanner's identity to its owner. */
    private val onPreviewChanged: (Scanner, Map<String, Any>?) -> Unit = { _, _ -> },
) {
    /** Completes once owned camera and preview resources finish asynchronous disposal. */
    val disposal: Deferred<Unit>
        get() = camera.disposal

    init {
        camera.onPreviewChanged = { description ->
            if (!isDisposed) onPreviewChanged(this, description)
        }
    }
    /** Borrows only the selected view; Flutter owns its lifetime. */
    private var view: ScannerConsumer? = null
    /** Identifier of the borrowed consumer, or null after its selection is revoked. */
    val viewId: Int?
        get() = view?.viewId

    /** Lazily allocated worker shared by camera frame callbacks. */
    private var analysisExecutor: ExecutorService? = null
    /** Protects analysis eligibility and listener membership across threads. */
    private val scanJobLock = Any()
    /** Parent job whose cancellation invalidates in-flight recognition results. */
    private var scanJob: CompletableJob? = null
    /** Caches scan crop geometry on the single analysis worker. */
    private val scanAreaState = ScanAreaState()
    /** Latest recognition region, published from controls to the analysis worker. */
    @Volatile private var cropArea: RecognizeVisorCropRect? = null
    /** Synchronous result listeners guarded by [scanJobLock]. */
    private val scanResultListeners = linkedSetOf<OnScanResultListener>()

    /** Terminal failure published to control and analysis threads. */
    @Volatile private var closeCause: Throwable? = null
    /** Current camera-control coroutine, replaced or cancelled by subsequent work. */
    private var operation: Deferred<Unit>? = null
    /** Only transient focus may be replaced by another gesture. */
    private var operationIsFocus = false
    /** Active subscription lifetime used to reject queued results from older scans. */
    private var scan: Scan? = null
    /** Borrows the Activity's real lifecycle without mirroring its state or observing events. */
    private var lifecycleOwner: LifecycleOwner? = null
    /** Whether terminal cleanup has revoked further scanner work. */
    val isDisposed: Boolean
        get() = closeCause != null

    /** Selects the consumer before binding so release can interrupt an unfinished capture. */
    fun select(target: ScannerConsumer) {
        check(!isDisposed) { "Scanner is disposed" }

        cancelOperation()
        pauseScan()

        view = target
        camera.updateGeometry(target.size)
    }

    /** Applies one Dart snapshot after the plugin has obtained camera permission. */
    suspend fun capture(configuration: ScannerConfiguration) =
        runOperation { id ->
            currentCoroutineContext().ensureActive()
            connection.awaitReady(::bindCamera)
            connection.control(id, CameraControlOperation.FOCUS, camera::resetFocus)
            cropArea = configuration.cropArea
            connection.control(id, CameraControlOperation.ZOOM) {
                camera.setZoomRatio(configuration.zoomRatio)
            }
            connection.control(id, CameraControlOperation.TORCH) {
                camera.setTorch(configuration.torchEnabled)
            }

            currentCoroutineContext().ensureActive()
            // Analysis starts only after Dart installs its native result endpoint.
        }

    /** Applies zoom through the current cancellable camera-control operation. */
    suspend fun setZoomRatio(value: Float) = runOperation { id ->
        connection.control(id, CameraControlOperation.ZOOM) { camera.setZoomRatio(value) }
    }

    /** Applies the torch state through the current camera-control operation. */
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
                        val size = view?.size ?: throw PluginError.CameraSessionDisposed
                        camera.focus(
                            resetDelayMs,
                            size.width / 2F + offsetX,
                            size.height / 2F + offsetY,
                        )
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(PluginConstants.LOG_TAG, "Camera focus failed", error)
            }
        }
    }

    /** Updates the cooldown and starts or resumes recognition for the selected widget. */
    fun startScan(periodMs: Int) {
        val id = viewId ?: return
        setScanPeriod(periodMs)
        if (scan == null) {
            scan = Scan(id)
        }
        scan?.resumeIfReady()
    }

    /** Revokes the subscription and invalidates analysis even if cancellation fails. */
    fun pauseScan() {
        val previous = scan
        scan = null
        val failures = ExceptionCollector()
        failures.attempt { previous?.cancel() }
        failures.attempt(this::pauseAnalysis)

        failures.throwIfFailed()
    }

    /** Publishes a new recognition crop for subsequent frames. */
    fun setCropArea(crop: RecognizeVisorCropRect) {

        cropArea = crop
    }

    /** Revokes the consumer without stopping the shared camera stream. */
    fun releaseCamera() {
        if (viewId == null) return
        view = null

        val failures = ExceptionCollector()
        failures.attempt(::cancelOperation)
        failures.attempt(::pauseScan)

        failures.throwIfFailed()
    }

    /** Copies the selected widget's viewport size to the shared camera adapter. */
    fun updateGeometry() {
        view?.let { camera.updateGeometry(it.size) }
    }

    /** Releases capture ownership and unbinds the reusable camera stream. */
    fun pauseCamera() {
        releaseCamera()
        connection.reset()
        camera.unbind()
    }

    /** Maps the configured crop center into viewport focus offsets. */
    fun focusCropCenter(resetDelayMs: Long) {
        val size = view?.size ?: return
        focus(
            resetDelayMs,
            ((cropArea?.centerOffsetX ?: 0.0) * size.width / 2).toFloat(),
            ((cropArea?.centerOffsetY ?: 0.0) * size.height / 2).toFloat(),
        )
    }

    /** Borrows the Activity lifecycle, detaching any different previous owner. */
    fun attachActivity(lifecycle: Lifecycle) {
        if (isDisposed || lifecycleOwner?.lifecycle === lifecycle) return
        if (lifecycleOwner != null) detachActivity()
        lifecycleOwner =
            object : LifecycleOwner {
                /** The Activity's lifecycle itself, without a mirrored state machine. */
                override val lifecycle: Lifecycle = lifecycle
            }
    }

    /**
     * Drops the old Activity binding; Dart's next capture supplies settings for its replacement.
     */
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

        val executor =
            synchronized(scanJobLock) {
                closeCause = cause
                pauseAnalysis()
                scanResultListeners.clear()
                analysisExecutor.also { analysisExecutor = null }
            }
        view = null
        lifecycleOwner = null
        val failures = ExceptionCollector()
        failures.attempt(::cancelOperation)
        failures.attempt(::pauseScan)

        failures.attempt(camera::dispose)
        failures.attempt { executor?.shutdownNow() }
        failures.attempt(analyzer::dispose)
        failures.attempt { connection.dispose(cause) }
        failures.attempt { scope.cancel() }
        failures.attempt { onReleased(this, cause) }
        failures.throwIfFailed()
    }

    /** Binds SDK callbacks to this Activity owner and ignores callbacks after replacement. */
    private fun bindCamera(onInit: () -> Unit) {
        val owner = lifecycleOwner ?: throw PluginError.CameraSessionDisposed
        try {
            camera.bind(
                owner,
                analysisExecutor(),
                this::analyzeFrame,
                onAvailabilityChanged = { availability ->
                    post {
                        if (lifecycleOwner !== owner) return@post
                        connection.onAvailabilityChanged(availability, viewId)
                        scan?.resumeIfReady()
                    }
                },
                onInit = { post { if (lifecycleOwner === owner) onInit() } },
                onError = { error -> post { if (lifecycleOwner === owner) dispose(error) } },
            )
        } catch (error: Exception) {
            try {
                dispose(error)
            } catch (cleanup: Exception) {
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

    /** Clears operation ownership before cancelling the outstanding SDK wait. */
    private fun cancelOperation() {
        val previous = operation
        operation = null
        previous?.cancel()
    }

    /** Schedules an SDK callback only while the scanner remains live. */
    private fun post(action: () -> Unit) {
        scope.launch { if (!isDisposed) action() }
    }

    /**
     * Subscription lifetime rejects queued results even across release/recapture of the same ID.
     *
     * @property id Selected widget whose results belong to this subscription lifetime.
     */
    private inner class Scan(private val id: Int) {
        /** Main-thread result jobs cancelled independently for this scan subscription. */
        private val deliveries =
            CoroutineScope(
                scope.coroutineContext +
                    SupervisorJob(scope.coroutineContext[Job]) +
                    mainHandler.asCoroutineDispatcher()
            )
        /** Analysis listener owned exclusively by this scan lifetime. */
        private val subscription = subscribeToScanResults { barcode ->
            deliveries.launch {
                if (scan === this@Scan && viewId == id && connection.isReady) onResult(id, barcode)
            }
        }

        /** Enables analysis only for this selected, ready scan and drops stale deliveries. */
        fun resumeIfReady() {
            if (scan === this && viewId == id && connection.isReady) {
                resumeAnalysis()
            } else {
                // Closing and reopening must not revive results from the previous camera stream.
                pauseAnalysis()
                deliveries.coroutineContext.cancelChildren()
            }
        }

        /** Cancels queued result delivery and unregisters the analysis listener. */
        fun cancel() {
            try {
                deliveries.cancel()
            } finally {
                subscription.cancel()
            }
        }
    }

    /** Returns the shared analysis worker, rejecting use after disposal. */
    private fun analysisExecutor(): ExecutorService =
        synchronized(scanJobLock) {
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
        synchronized(scanJobLock) { if (!isDisposed) analyzer.updatePeriod(periodMs) }
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
    private fun createAnalysisJob(): CompletableJob? =
        synchronized(scanJobLock) {
            val activeScanJob = scanJob?.takeIf { it.isActive } ?: return@synchronized null
            Job(activeScanJob)
        }

    /** Delivers results to a listener snapshot while rechecking cancellation and membership. */
    private fun emitScanResult(result: Barcode, analysisJob: Job) =
        synchronized(scanJobLock) {
            // A listener can synchronously pause/restart scanning or cancel another subscription.
            // Snapshot iteration tolerates those edits; recheck eligibility before each delivery.
            for (listener in scanResultListeners.toList()) {
                if (!analysisJob.isActive) return
                if (listener in scanResultListeners) listener(result)
            }
        }

    internal companion object {
        /** Builds owned resources and cleans up a camera if analyzer creation fails. */
        fun create(
            cameraFactory: () -> Camera,
            analyzerFactory: () -> ImageBarcodeAnalyzer,
            mainHandler: Handler,
            onReleased: (Scanner, Throwable) -> Unit,
            onResult: (Int, Barcode) -> Unit,
            onPreviewChanged: (Scanner, Map<String, Any>?) -> Unit,
        ): Scanner {
            val camera = cameraFactory()
            var analyzer: ImageBarcodeAnalyzer? = null
            try {
                analyzer = analyzerFactory()
                return Scanner(camera, analyzer, mainHandler, onReleased, onResult,
                    onPreviewChanged = onPreviewChanged)
            } catch (error: Exception) {
                val failures = ExceptionCollector(error)
                failures.attempt(camera::dispose)
                failures.attempt { analyzer?.dispose() }
                failures.throwIfFailed()
                throw error
            }
        }

        /** Creates the scanner's main-thread supervisor with uncaught-error logging. */
        fun createScope(): CoroutineScope =
            MainScope() +
                CoroutineExceptionHandler { _, error ->
                    Log.e(PluginConstants.LOG_TAG, "Scanner failed", error)
                }
    }
}

/** Receives decoded results synchronously on the analysis thread. */
typealias OnScanResultListener = (result: Barcode) -> Unit

/** Handle used to stop receiving scanner results. */
class ScanResultSubscription internal constructor(onCancel: () -> Unit) {
    /** Atomically owned cancellation callback, consumed at most once. */
    private val cancellation = AtomicReference<(() -> Unit)?>(onCancel)

    /** Stops delivering scan results to the listener associated with this subscription. */
    fun cancel() {
        // Clear ownership before invoking user code, including reentrant or throwing cleanup.
        cancellation.getAndSet(null)?.invoke()
    }
}
