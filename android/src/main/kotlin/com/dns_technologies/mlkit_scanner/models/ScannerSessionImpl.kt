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
 * Runtime intent and configuration belong to each registered platform view. Camera ownership is an
 * explicit view-id claim: capture moves the preview and release drops ownership without
 * deleting the view state. Registration, manual resume, and disposal never infer route order. A
 * temporary empty registry pauses work while retaining resources across a short route transition.
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

    override fun createView(
        context: Context,
        viewId: Int,
        initialZoom: Double?,
        initialCropRect: RecognizeVisorCropRect?,
        initialFlashEnabled: Boolean?,
    ): ScannerView {
        check(!isReleased) { "Cannot add a scanner view to a released session" }
        check(viewId !in views) { "Scanner platform view $viewId already exists" }
        cancelDeferredRelease()
        val view = ScannerView(context, scanner) { disposePlatformView(viewId) }
        views[viewId] = ScannerViewState(
            viewId = viewId,
            view = view,
            zoom = initialZoom?.toFloat(),
            torchEnabled = initialFlashEnabled,
            cropArea = initialCropRect,
        )
        return view
    }

    /** Attaches and registers an already-created view; exposed to keep JVM tests Android-free. */
    internal fun attachView(
        viewId: Int,
        view: ScannerView,
        initialZoom: Double? = null,
        initialCropRect: RecognizeVisorCropRect? = null,
        initialFlashEnabled: Boolean? = null,
    ) {
        check(!isReleased) { "Cannot add a scanner view to a released session" }
        check(viewId !in views) { "Scanner platform view $viewId already exists" }
        views[viewId] = ScannerViewState(
            viewId = viewId,
            view = view,
            zoom = initialZoom?.toFloat(),
            torchEnabled = initialFlashEnabled,
            cropArea = initialCropRect,
        )
        cancelDeferredRelease()
    }

    override suspend fun captureCamera(
        viewId: Int,
        requestCameraPermission: suspend () -> Boolean,
    ) {
        if (isReleased) throw PluginError.CameraSessionDisposed
        val viewState = requireView(viewId)
        val previousCapture = capturedViewState()
        views.values.forEach { it.isCameraOwner = it === viewState }
        moveCamera(previousCapture, viewState)

        if (!requestCameraPermission()) {
            if (isCurrentCapture(viewId)) releaseCapturedCamera()
            throw PluginError.AuthorizationCameraError
        }
        if (!isCurrentCapture(viewId)) return
        if (!viewState.cameraRequested) return
        val isRestoring = viewState.cameraStarted
        viewState.cameraStarted = true
        updateCameraLifecycle()

        if (scanner.isActive()) {
            if (isRestoring) {
                applyRestoredCameraControls(viewState)
            } else {
                applyCameraControlsIfActive(viewState)
            }
        } else {
            initializeCamera().await()
        }
    }

    override fun releaseCamera(viewId: Int) {
        if (views[viewId]?.isCameraOwner == true) releaseCapturedCamera()
    }

    override fun resumeCamera(viewId: Int) {
        val viewState = requireView(viewId)
        viewState.cameraRequested = true
        if (viewState !== capturedViewState()) return

        viewState.cameraStarted = true
        updateCameraLifecycle()
        applyScanState()
        if (scanner.isActive()) {
            restoreCameraControlsIfActive(viewState)
        } else {
            initializationScope.launch {
                try {
                    initializeCamera().await()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (!isReleased && viewState === capturedViewState()) {
                        Log.w(TAG, "Unable to resume captured scanner camera", error)
                    }
                }
            }
        }
    }

    override fun pauseCamera(viewId: Int) {
        val viewState = views[viewId] ?: return
        viewState.cameraRequested = false
        if (viewState.view.hasPreview()) viewState.configurationApplied = false
        applyViewStateIfCaptured(viewState)
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
            if (viewState === capturedViewState()) {
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
        viewState.scanRequestedByView = true
        applyScanPeriodIfActive(viewState)
        applyViewStateIfCaptured(viewState)
    }

    override fun pauseScan(viewId: Int) {
        val viewState = views[viewId] ?: return
        viewState.scanRequestedByView = false
        applyViewStateIfCaptured(viewState)
    }

    override fun updateScanPeriod(viewId: Int, periodMs: Int) {
        val viewState = requireViewCameraReady(viewId)
        viewState.scanPeriodMs = periodMs
        applyScanPeriodIfActive(viewState)
    }

    override suspend fun setZoom(viewId: Int, value: Float) {
        cameraControlMutex.withLock {
            val viewState = requireViewCameraReady(viewId)
            if (viewState === capturedViewState()) {
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
        if (viewState !== capturedViewState()) return
        scanner.setCropArea(viewState.cropArea)
        viewState.cropArea?.let(viewState.view::renderCropArea)
    }

    private fun restoreCropAreaIfActive(viewState: ScannerViewState) {
        if (viewState !== capturedViewState()) return
        scanner.setCropArea(viewState.cropArea)
        viewState.view.redrawCropArea()
    }

    private fun applyScanPeriodIfActive(viewState: ScannerViewState) {
        if (viewState !== capturedViewState()) return
        viewState.scanPeriodMs?.let(scanner::updateScanPeriod)
    }

    /** Mirrors Flutter's native PlatformView disposal in JVM tests. */
    internal fun disposeView(viewId: Int) {
        val viewState = views[viewId] ?: return
        if (viewState.isCameraOwner) releaseCapturedCamera()
        views.remove(viewId)
        viewState.view.setScanActive(false)
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

    private fun moveCamera(previousHost: ScannerViewState?, viewState: ScannerViewState) {
        if (previousHost !== viewState) {
            previousHost?.let {
                scanner.pauseScan()
                cancelPendingResultDeliveries()
                it.configurationApplied = false
                it.view.detachPreview()
            }
            attachPreview(viewState)
        } else if (viewState.configurationApplied) {
            // A repeated capture is an explicit request to restore this view's retained state.
            // Invalidate the applied marker so captureCamera reapplies zoom and torch too.
            viewState.configurationApplied = false
            applyCropAreaIfActive(viewState)
            applyScanPeriodIfActive(viewState)
            viewState.view.setScanActive(false)
            if (scanner.isActive()) scanner.hidePreview()
        }
        updateCameraLifecycle()
        applyScanState()
    }

    private fun disposePlatformView(viewId: Int) {
        disposeView(viewId)
    }

    private fun releaseCapturedCamera() {
        val captured = capturedViewState()
        captured?.let { viewState ->
            viewState.isCameraOwner = false
            viewState.configurationApplied = false
            viewState.view.setScanActive(false)
            if (scanner.isActive()) scanner.hidePreview()
            viewState.view.detachPreview()
        }
        updateCameraLifecycle()
        applyScanState()
    }

    private fun isCurrentCapture(viewId: Int): Boolean =
        !isReleased && views[viewId]?.isCameraOwner == true

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
        val shouldScan = capturedViewState()?.let { viewState ->
            viewState.scanRequestedByView &&
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
        capturedViewState()?.view?.setScanActive(shouldScan)
    }

    private fun updateCameraLifecycle() {
        if (isReleased) return
        val shouldRun = capturedViewState()?.let { viewState ->
            viewState.cameraStarted && viewState.cameraRequested && !hostPaused
        } == true
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
            capturedViewState()?.let { viewState ->
                viewState.configurationApplied = false
                scanner.hidePreview()
            }
        }
        updateCameraLifecycle()
        applyScanState()
        if (!isPaused) {
            capturedViewState()?.let { viewState ->
                restoreCropAreaIfActive(viewState)
                restoreCameraControlsIfActive(viewState)
            }
        }
    }

    private fun applyViewStateIfCaptured(viewState: ScannerViewState) {
        if (viewState !== capturedViewState()) return
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
            capturedViewState()?.view?.bindFocus()
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
                            capturedViewState()?.let { viewState ->
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
                if (!isReleased && viewState === capturedViewState()) {
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
            viewState === capturedViewState()

    private fun capturedViewState(): ScannerViewState? =
        views.values.firstOrNull { it.isCameraOwner }

    private fun enqueueScanResult(result: Barcode) {
        lateinit var delivery: Runnable
        delivery = Runnable {
            val shouldDeliver = synchronized(resultDeliveryLock) {
                pendingResultDeliveries.remove(delivery) &&
                    deliverScanResults
            }
            if (shouldDeliver) {
                capturedViewState()?.viewId?.let { viewId ->
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
        /** True only for the one registered view that currently owns the shared camera preview. */
        var isCameraOwner: Boolean = false,
        var cameraStarted: Boolean = false,
        var cameraRequested: Boolean = true,
        /** User scan intent retained across capture, host-lifecycle, and preview system pauses. */
        var scanRequestedByView: Boolean = false,
        var scanPeriodMs: Int? = null,
        var zoom: Float? = null,
        var torchEnabled: Boolean? = null,
        var cropArea: RecognizeVisorCropRect? = null,
        var configurationApplied: Boolean = false,
    )
}
