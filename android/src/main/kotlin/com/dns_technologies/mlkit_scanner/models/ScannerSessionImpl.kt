package com.dns_technologies.mlkit_scanner.models

import android.content.Context
import android.os.Handler
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.dns_technologies.mlkit_scanner.PluginError
import com.dns_technologies.mlkit_scanner.scanner.Scanner
import com.dns_technologies.mlkit_scanner.scanner.ScannerView
import com.dns_technologies.mlkit_scanner.scanner.models.Barcode
import com.dns_technologies.mlkit_scanner.scanner.models.RecognizeVisorCropRect
import com.dns_technologies.mlkit_scanner.scanner.models.ScanResultSubscription
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns one CameraX/scanner pipeline shared by every registered platform view.
 *
 * Runtime intent and configuration belong to each platform view. The one view that physically hosts
 * the shared preview drives the camera/analyzer pipeline and receives scan results. Initial camera
 * startup and explicit resume select that view; view registration and disposal never infer route order.
 * A temporary empty registry pauses work while retaining native resources across a short route transition.
 */
internal class ScannerSessionImpl(
    private val scanner: Scanner,
    private val mainHandler: Handler,
    private val onScanResult: (Int, Barcode) -> Unit,
    private val onReleased: () -> Unit,
    private val initializationScope: CoroutineScope = MainScope(),
    lifecycleRegistryFactory: (LifecycleOwner) -> LifecycleRegistry = ::LifecycleRegistry,
) : ScannerSession, LifecycleOwner, DefaultLifecycleObserver {
    private val lifecycleRegistry = lifecycleRegistryFactory(this)
    private val views = mutableMapOf<Int, ScannerViewState>()
    private val resultDeliveryLock = Any()
    private val pendingResultDeliveries = mutableSetOf<Runnable>()
    private val cameraControlMutex = Mutex()
    private var scanSubscription: ScanResultSubscription? =
        scanner.subscribeToScanResults(::enqueueScanResult)
    private var hostLifecycle: Lifecycle? = null
    private var cameraInitialization: CompletableDeferred<Unit>? = null
    private var deferredRelease: Runnable? = null
    private var hostPaused = true

    @Volatile
    private var deliverScanResults = false

    @Volatile
    private var isReleased = false

    init {
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override fun createView(context: Context, viewId: Int): ScannerView {
        check(!isReleased) { "Cannot add a scanner view to a released session" }
        val view = ScannerView(context, scanner) { disposeView(viewId) }
        attachView(viewId, view)
        return view
    }

    /** Registers an already-created view; exposed internally to keep JVM tests Android-free. */
    internal fun attachView(viewId: Int, view: ScannerView) {
        check(!isReleased) { "Cannot add a scanner view to a released session" }
        check(viewId !in views) { "Scanner view $viewId is already registered" }

        cancelDeferredRelease()
        views[viewId] = ScannerViewState(
            viewId = viewId,
            view = view,
        )
    }

    override suspend fun startCamera(
        viewId: Int,
        initialZoom: Double?,
        initialCropRect: RecognizeVisorCropRect?,
        initialFlashEnabled: Boolean?,
    ) {
        if (isReleased) throw PluginError.CameraSessionDisposed
        val viewState = requireView(viewId)

        if (!viewState.cameraStarted) {
            viewState.cameraStarted = true
            viewState.zoom = initialZoom?.toFloat()
            viewState.cropArea = initialCropRect
            viewState.torchEnabled = initialFlashEnabled
        }
        activateView(viewState)

        if (scanner.isActive()) {
            if (!viewState.configurationApplied) applyCameraControlsIfActive(viewState)
        } else {
            initializeCamera().await()
        }
    }

    override fun resumeCamera(viewId: Int) {
        val viewState = requireViewCameraReady(viewId)
        viewState.cameraRequested = true
        activateView(viewState)
        restoreCameraControlsIfActive(viewState)
    }

    override fun pauseCamera(viewId: Int) {
        val viewState = views[viewId] ?: return
        viewState.cameraRequested = false
        if (viewState.view.hasPreview()) viewState.configurationApplied = false
        applyViewStateIfActive(viewState)
    }

    override fun attachHostLifecycle(lifecycle: Lifecycle) {
        if (isReleased) return
        if (hostLifecycle === lifecycle) {
            updateHostPaused(!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
            return
        }

        hostLifecycle?.removeObserver(this)
        hostLifecycle = lifecycle
        updateHostPaused(!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        lifecycle.addObserver(this)
    }

    override fun detachHostLifecycle() {
        hostLifecycle?.removeObserver(this)
        hostLifecycle = null
        updateHostPaused(true)
    }

    override fun onResume(owner: LifecycleOwner) {
        updateHostPaused(false)
    }

    override fun onPause(owner: LifecycleOwner) {
        updateHostPaused(true)
    }

    override suspend fun toggleFlashLight(viewId: Int) {
        cameraControlMutex.withLock {
            val viewState = requireViewCameraReady(viewId)
            val enabled = viewState.torchEnabled != true
            if (viewState === previewHostState()) {
                scanner.setTorch(enabled).await()
                if (isReleased) throw PluginError.CameraSessionDisposed
            } else if (enabled && !scanner.isFlashSupported()) {
                throw PluginError.DeviceHasNotFlash
            }
            viewState.torchEnabled = enabled
        }
    }

    override fun startScan(viewId: Int, periodMs: Int) {
        val viewState = requireViewCameraReady(viewId)
        viewState.scanPeriodMs = periodMs
        viewState.scanRequested = true
        applyScanPeriodIfActive(viewState)
        applyViewStateIfActive(viewState)
    }

    override fun pauseScan(viewId: Int) {
        val viewState = views[viewId] ?: return
        viewState.scanRequested = false
        applyViewStateIfActive(viewState)
    }

    override fun updateScanPeriod(viewId: Int, periodMs: Int) {
        val viewState = requireViewCameraReady(viewId)
        viewState.scanPeriodMs = periodMs
        applyScanPeriodIfActive(viewState)
    }

    override suspend fun setZoom(viewId: Int, value: Float) {
        cameraControlMutex.withLock {
            val viewState = requireViewCameraReady(viewId)
            if (viewState === previewHostState()) {
                scanner.setZoom(value).await()
                if (isReleased) throw PluginError.CameraSessionDisposed
            }
            viewState.zoom = value
        }
    }

    override fun setCropArea(viewId: Int, cropRect: RecognizeVisorCropRect) {
        val viewState = requireViewCameraReady(viewId)
        viewState.cropArea = cropRect
        applyCropAreaIfActive(viewState)
    }

    private fun applyCropAreaIfActive(viewState: ScannerViewState) {
        if (viewState !== previewHostState()) return
        scanner.setCropArea(viewState.cropArea)
        viewState.cropArea?.let(viewState.view::renderCropArea)
    }

    private fun applyScanPeriodIfActive(viewState: ScannerViewState) {
        if (viewState !== previewHostState()) return
        viewState.scanPeriodMs?.let(scanner::updateScanPeriod)
    }

    override fun disposeView(viewId: Int) {
        val viewState = views.remove(viewId) ?: return
        val hostedPreview = viewState.view.hasPreview()
        if (hostedPreview) {
            scanner.pauseScan()
            cancelPendingResultDeliveries()
        }
        viewState.view.disposeFromSession()

        updateCameraLifecycle()
        if (views.isEmpty()) {
            scheduleDeferredRelease()
        }
        applyScanState()
    }

    override fun release() {
        if (isReleased) return
        detachHostLifecycle()
        isReleased = true
        cancelDeferredRelease()
        deliverScanResults = false
        cancelPendingResultDeliveries()

        cameraInitialization?.completeExceptionally(PluginError.CameraSessionDisposed)
        cameraInitialization = null
        initializationScope.cancel()
        scanSubscription?.cancel()
        scanSubscription = null

        views.values.forEach { it.view.disposeFromSession() }
        views.clear()

        scanner.dispose()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        onReleased()
    }

    private fun activateView(viewState: ScannerViewState) {
        val previewHostState = previewHostState()
        if (previewHostState !== viewState) {
            previewHostState?.let { previousHost ->
                scanner.pauseScan()
                cancelPendingResultDeliveries()
                previousHost.view.detachPreview()
            }
            attachPreview(viewState)
        }
        updateCameraLifecycle()
        applyScanState()
    }

    private fun attachPreview(viewState: ScannerViewState) {
        viewState.configurationApplied = false
        viewState.view.attachPreview(::applyScanState)
        applyCropAreaIfActive(viewState)
        applyScanPeriodIfActive(viewState)
        viewState.view.setScanActive(false)
        if (scanner.isActive()) {
            scanner.hidePreview()
            viewState.view.bindFocus()
        }
    }

    private fun applyScanState() {
        val shouldScan = previewHostState()?.let { viewState ->
            viewState.scanRequested &&
                viewState.cameraRequested &&
                viewState.configurationApplied &&
                viewState.view.isPreviewReady() &&
                !hostPaused &&
                !isReleased
        } == true
        deliverScanResults = shouldScan
        if (shouldScan) {
            scanner.resumeScan()
        } else {
            scanner.pauseScan()
            cancelPendingResultDeliveries()
        }
        previewHostState()?.view?.setScanActive(shouldScan)
    }

    private fun updateCameraLifecycle() {
        if (isReleased) return
        val shouldRun = previewHostState()?.cameraRequested == true && !hostPaused
        lifecycleRegistry.currentState = if (shouldRun) {
            Lifecycle.State.RESUMED
        } else {
            Lifecycle.State.CREATED
        }
    }

    private fun updateHostPaused(isPaused: Boolean) {
        if (hostPaused == isPaused) return
        hostPaused = isPaused
        if (isPaused && scanner.isActive()) {
            previewHostState()?.let { viewState ->
                viewState.configurationApplied = false
                scanner.hidePreview()
            }
        }
        updateCameraLifecycle()
        applyScanState()
        if (!isPaused) previewHostState()?.let(::restoreCameraControlsIfActive)
    }

    private fun applyViewStateIfActive(viewState: ScannerViewState) {
        if (viewState !== previewHostState()) return
        updateCameraLifecycle()
        applyScanState()
    }

    private fun initializeCamera(): CompletableDeferred<Unit> {
        if (isReleased) return failedInitialization(PluginError.CameraSessionDisposed)
        cameraInitialization?.let { return it }
        if (scanner.isActive()) return CompletableDeferred(Unit)

        val initialization = CompletableDeferred<Unit>()
        cameraInitialization = initialization
        initialization.invokeOnCompletion {
            if (cameraInitialization === initialization) cameraInitialization = null
        }

        fun fail(error: Throwable) {
            if (initialization.completeExceptionally(error)) release()
        }

        fun complete() {
            if (isReleased || initialization.isCompleted) return
            previewHostState()?.view?.bindFocus()
            applyScanState()
            initialization.complete(Unit)
        }

        try {
            scanner.startCamera(
                lifecycleOwner = this,
                onInit = {
                    if (isReleased) return@startCamera
                    initializationScope.launch {
                        try {
                            previewHostState()?.let { viewState ->
                                applyCameraControlsIfActive(viewState)
                            }
                            complete()
                        } catch (error: Exception) {
                            fail(error)
                        }
                    }
                },
                onError = ::fail,
            )
        } catch (error: Exception) {
            fail(error)
        }

        return initialization
    }

    private fun failedInitialization(error: Throwable): CompletableDeferred<Unit> =
        CompletableDeferred<Unit>().also { it.completeExceptionally(error) }

    private suspend fun applyCameraControlsIfActive(viewState: ScannerViewState) {
        cameraControlMutex.withLock {
            if (!canApplyCameraControls(viewState)) return
            scanner.awaitCameraOpen().await()
            if (!canApplyCameraControls(viewState)) return
            scanner.setZoom(viewState.zoom ?: DEFAULT_ZOOM).await()
            if (!canApplyCameraControls(viewState)) return
            scanner.setTorch(viewState.torchEnabled == true).await()
            if (!canApplyCameraControls(viewState)) return
            viewState.configurationApplied = true
            scanner.showPreview()
            applyScanState()
        }
    }

    private fun restoreCameraControlsIfActive(viewState: ScannerViewState) {
        if (viewState.configurationApplied || !canApplyCameraControls(viewState)) return
        initializationScope.launch {
            try {
                applyRestoredCameraControls(viewState)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (!isReleased && viewState === previewHostState()) {
                    Log.w(TAG, "Unable to restore scanner view camera controls", error)
                }
            }
        }
    }

    private suspend fun applyRestoredCameraControls(viewState: ScannerViewState) {
        try {
            applyCameraControlsIfActive(viewState)
        } catch (error: PluginError) {
            if (
                error !== PluginError.DeviceHasNotFlash ||
                viewState.torchEnabled != true ||
                !canApplyCameraControls(viewState)
            ) {
                throw error
            }
            viewState.torchEnabled = false
            applyCameraControlsIfActive(viewState)
        }
    }

    private fun canApplyCameraControls(viewState: ScannerViewState): Boolean =
        !isReleased &&
            scanner.isActive() &&
            viewState.cameraStarted &&
            viewState.cameraRequested &&
            !hostPaused &&
            viewState === previewHostState()

    private fun previewHostState(): ScannerViewState? =
        views.values.firstOrNull { it.view.hasPreview() }

    private fun enqueueScanResult(result: Barcode) {
        lateinit var delivery: Runnable
        delivery = Runnable {
            val shouldDeliver = synchronized(resultDeliveryLock) {
                pendingResultDeliveries.remove(delivery) &&
                    deliverScanResults
            }
            if (shouldDeliver) {
                previewHostState()?.viewId?.let { viewId ->
                    onScanResult(viewId, result)
                }
            }
        }

        synchronized(resultDeliveryLock) {
            if (!deliverScanResults || isReleased) return
            pendingResultDeliveries += delivery
            if (!mainHandler.post(delivery)) pendingResultDeliveries -= delivery
        }
    }

    private fun requireView(viewId: Int): ScannerViewState =
        views[viewId] ?: throw PluginError.CameraIsNotInitialized

    private fun requireViewCameraReady(viewId: Int): ScannerViewState =
        requireView(viewId).also { viewState ->
            requireCameraReady()
            if (!viewState.cameraStarted) throw PluginError.CameraIsNotInitialized
        }

    private fun requireCameraReady() {
        if (isReleased) throw PluginError.CameraSessionDisposed
        if (cameraInitialization != null || !scanner.isActive()) {
            throw PluginError.CameraIsNotInitialized
        }
    }

    private fun cancelPendingResultDeliveries() {
        val callbacks = synchronized(resultDeliveryLock) {
            pendingResultDeliveries.toList().also { pendingResultDeliveries.clear() }
        }
        callbacks.forEach(mainHandler::removeCallbacks)
    }

    private fun scheduleDeferredRelease() {
        if (isReleased || deferredRelease != null) return
        val releaseTask = Runnable {
            deferredRelease = null
            if (views.isEmpty()) release()
        }
        deferredRelease = releaseTask
        if (!mainHandler.postDelayed(releaseTask, NAVIGATION_GRACE_PERIOD_MS)) {
            deferredRelease = null
            release()
        }
    }

    private fun cancelDeferredRelease() {
        deferredRelease?.let(mainHandler::removeCallbacks)
        deferredRelease = null
    }

    internal companion object {
        /** Time to retain a paused scanner pipeline while Flutter replaces its platform view. */
        const val NAVIGATION_GRACE_PERIOD_MS = 300L

        private const val DEFAULT_ZOOM = 0.0F
        private const val TAG = "MlkitScannerSession"
    }

    private data class ScannerViewState(
        val viewId: Int,
        val view: ScannerView,
        var cameraStarted: Boolean = false,
        var cameraRequested: Boolean = true,
        var scanRequested: Boolean = false,
        var scanPeriodMs: Int? = null,
        var zoom: Float? = null,
        var torchEnabled: Boolean? = null,
        var cropArea: RecognizeVisorCropRect? = null,
        var configurationApplied: Boolean = false,
    )
}
