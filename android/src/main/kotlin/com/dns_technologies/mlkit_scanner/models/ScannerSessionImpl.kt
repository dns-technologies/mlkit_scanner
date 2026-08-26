package com.dns_technologies.mlkit_scanner.models

import android.content.Context
import android.os.Handler
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns one CameraX/scanner pipeline shared by every registered platform view.
 *
 * Runtime camera and scan controls are session-wide and the last command wins. Initial camera
 * parameters belong only to the first call that starts initialization. The view registry exists
 * only to host the one Android preview and to delay resource release across short route transitions.
 * A temporary empty registry pauses effective camera and scan work without changing the states
 * requested by the user, so a replacement view can restore them without rebuilding the pipeline.
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
    private val views = linkedMapOf<Int, ScannerView>()
    private val resultDeliveryLock = Any()
    private val pendingResultDeliveries = mutableSetOf<Runnable>()
    private val torchMutex = Mutex()
    private val zoomMutex = Mutex()
    private var scanSubscription: ScanResultSubscription? =
        scanner.subscribeToScanResults(::enqueueScanResult)
    private var hostLifecycle: Lifecycle? = null
    private var cameraInitialization: CompletableDeferred<Unit>? = null
    private var deferredRelease: Runnable? = null
    private var scanRequested = false
    private var cameraRequested = false
    private var cameraPaused = false
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
        previewHost()?.let { previousHost ->
            scanner.pauseScan()
            cancelPendingResultDeliveries()
            previousHost.detachPreview()
        }
        views[viewId] = view
        attachPreview(view)
        updateCameraLifecycle()
        applyScanState()
    }

    override suspend fun startCamera(
        viewId: Int,
        initialZoom: Double?,
        initialCropRect: RecognizeVisorCropRect?,
    ) {
        if (isReleased) throw PluginError.CameraSessionDisposed
        requireView(viewId)

        if (!cameraRequested) {
            cameraRequested = true
            cameraPaused = false
        }
        updateCameraLifecycle()
        applyScanState()

        initializeCamera(initialZoom, initialCropRect).await()
    }

    override fun resumeCamera() {
        requireCameraReady()
        cameraPaused = false
        updateCameraLifecycle()
        applyScanState()
    }

    override fun pauseCamera() {
        cameraPaused = true
        updateCameraLifecycle()
        applyScanState()
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

    override suspend fun toggleFlashLight() {
        torchMutex.withLock {
            requireCameraReady()
            scanner.toggleFlashLight().await()
            if (isReleased) throw PluginError.CameraSessionDisposed
        }
    }

    override fun startScan(periodMs: Int) {
        requireCameraReady()
        scanner.updateScanPeriod(periodMs)
        scanRequested = true
        applyScanState()
    }

    override fun pauseScan() {
        scanRequested = false
        applyScanState()
    }

    override fun updateScanPeriod(periodMs: Int) {
        requireCameraReady()
        scanner.updateScanPeriod(periodMs)
    }

    override suspend fun setZoom(value: Float) {
        zoomMutex.withLock {
            requireCameraReady()
            scanner.setZoom(value).await()
            if (isReleased) throw PluginError.CameraSessionDisposed
        }
    }

    override fun setCropArea(cropRect: RecognizeVisorCropRect) {
        requireCameraReady()
        applyCropArea(cropRect)
    }

    private fun applyCropArea(cropRect: RecognizeVisorCropRect) {
        scanner.setCropArea(cropRect)
        previewHost()?.renderCropArea(cropRect)
    }

    override fun disposeView(viewId: Int) {
        val view = views.remove(viewId) ?: return
        val hostedPreview = view.hasPreview()
        if (hostedPreview) {
            scanner.pauseScan()
            cancelPendingResultDeliveries()
        }
        view.disposeFromSession()

        if (hostedPreview) {
            views.values.lastOrNull()?.let(::attachPreview)
            applyScanState()
        }

        if (views.isEmpty()) {
            updateCameraLifecycle()
            if (!hostedPreview) applyScanState()
            scheduleDeferredRelease()
        }
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

        views.values.forEach(ScannerView::disposeFromSession)
        views.clear()

        scanner.dispose()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        onReleased()
    }

    private fun attachPreview(view: ScannerView) {
        view.attachPreview(::applyScanState)
        applyPreviewConfiguration(view)
        if (scanner.isActive()) view.bindFocus()
    }

    private fun applyPreviewConfiguration(view: ScannerView) {
        scanner.currentCropArea?.let(view::renderCropArea)
        view.setScanActive(scanRequested && views.isNotEmpty())
    }

    private fun applyScanState() {
        val shouldScan = scanRequested &&
            previewHost()?.isPreviewReady() == true &&
            !cameraPaused &&
            !hostPaused &&
            !isReleased
        deliverScanResults = shouldScan
        if (shouldScan) {
            scanner.resumeScan()
        } else {
            scanner.pauseScan()
            cancelPendingResultDeliveries()
        }
        previewHost()?.setScanActive(shouldScan)
    }

    private fun updateCameraLifecycle() {
        if (isReleased) return
        val shouldRun = views.isNotEmpty() && cameraRequested && !cameraPaused && !hostPaused
        lifecycleRegistry.currentState = if (shouldRun) {
            Lifecycle.State.RESUMED
        } else {
            Lifecycle.State.CREATED
        }
    }

    private fun updateHostPaused(isPaused: Boolean) {
        if (hostPaused == isPaused) return
        hostPaused = isPaused
        updateCameraLifecycle()
        applyScanState()
    }

    private fun initializeCamera(
        initialZoom: Double?,
        initialCropRect: RecognizeVisorCropRect?,
    ): CompletableDeferred<Unit> {
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
            previewHost()?.bindFocus()
            applyScanState()
            scanner.showPreview()
            initialization.complete(Unit)
        }

        try {
            initialCropRect?.let(::applyCropArea)
            scanner.startCamera(
                lifecycleOwner = this,
                onInit = {
                    if (isReleased) return@startCamera
                    initializationScope.launch {
                        try {
                            if (initialZoom != null) {
                                scanner.setZoom(initialZoom.toFloat()).await()
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

    private fun previewHost(): ScannerView? = previewHostEntry()?.value

    private fun previewHostEntry(): Map.Entry<Int, ScannerView>? =
        views.entries.firstOrNull { (_, view) -> view.hasPreview() }

    private fun enqueueScanResult(result: Barcode) {
        lateinit var delivery: Runnable
        delivery = Runnable {
            val shouldDeliver = synchronized(resultDeliveryLock) {
                pendingResultDeliveries.remove(delivery) &&
                    deliverScanResults
            }
            if (shouldDeliver) {
                previewHostEntry()?.key?.let { viewId ->
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

    private fun requireView(viewId: Int) {
        if (viewId !in views) throw PluginError.CameraIsNotInitialized
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
    }
}
