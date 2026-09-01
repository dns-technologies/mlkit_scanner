package com.dns_technologies.mlkit_scanner.models

import android.content.Context
import android.os.Handler
import androidx.camera.core.CameraControl
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.dns_technologies.mlkit_scanner.CameraControlOperation
import com.dns_technologies.mlkit_scanner.PluginError
import com.dns_technologies.mlkit_scanner.scanner.Scanner
import com.dns_technologies.mlkit_scanner.scanner.ScannerView
import com.dns_technologies.mlkit_scanner.scanner.components.camera.CameraAvailability
import com.dns_technologies.mlkit_scanner.scanner.components.camera.CameraCommand
import com.dns_technologies.mlkit_scanner.scanner.models.Barcode
import com.dns_technologies.mlkit_scanner.scanner.models.RecognizeVisorCropRect
import com.dns_technologies.mlkit_scanner.scanner.models.ScanResultSubscription
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Owns the desired scanner configuration and every operation submitted to the shared camera.
 *
 * All mutable session state is reduced by one actor on the supplied main scope. Each view retains
 * its desired configuration, while only the current [OwnerActivation] may apply it. Camera
 * callbacks only enqueue facts; they never restore controls themselves.
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
    private val events = Channel<SessionEvent>(Channel.UNLIMITED)
    private val views = mutableMapOf<Int, ScannerViewState>()
    private val pendingResultDeliveries = mutableSetOf<Runnable>()
    private val resultDeliveryLock = Any()

    private var hostLifecycle: Lifecycle? = null
    private var owner: OwnerActivation? = null
    private var cameraConnection: CameraConnection = CameraConnection.Unbound
    private var handoff: Handoff? = null
    private var deferredRelease: Runnable? = null
    private var hostPaused = true

    @Volatile
    private var scanTargetViewId: Int? = null

    @Volatile
    private var releaseRequested = false

    private var isReleased = false
    private var scanSubscription: ScanResultSubscription? =
        scanner.subscribeToScanResults(::enqueueScanResult)

    private val actorJob: Job = initializationScope.launch(start = CoroutineStart.UNDISPATCHED) {
        for (event in events) reduce(event)
    }

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
        check(!releaseRequested) { "Cannot add a scanner view to a released session" }
        val view = ScannerView(
            context = context,
            scanner = scanner,
            onFocusRequest = { resetDelayMs, offsetX, offsetY ->
                requestFocus(viewId, resetDelayMs, offsetX, offsetY)
            },
            onDispose = { disposePlatformView(viewId) },
        )
        registerView(viewId, view, initialZoom, initialCropRect, initialFlashEnabled)
        return view
    }

    /** Attaches an already-created view; exposed to keep JVM tests Android-free. */
    internal fun attachView(
        viewId: Int,
        view: ScannerView,
        initialZoom: Double? = null,
        initialCropRect: RecognizeVisorCropRect? = null,
        initialFlashEnabled: Boolean? = null,
    ) {
        check(!releaseRequested) { "Cannot add a scanner view to a released session" }
        registerView(viewId, view, initialZoom, initialCropRect, initialFlashEnabled)
    }

    private fun registerView(
        viewId: Int,
        view: ScannerView,
        initialZoom: Double?,
        initialCropRect: RecognizeVisorCropRect?,
        initialFlashEnabled: Boolean?,
    ) {
        dispatch(
            SessionEvent.RegisterView(
                viewId,
                view,
                initialZoom?.toFloat(),
                initialCropRect,
                initialFlashEnabled,
            ),
        )
    }

    override suspend fun captureCamera(
        viewId: Int,
        requestCameraPermission: suspend () -> Boolean,
    ) {
        if (releaseRequested) throw PluginError.CameraSessionDisposed
        val result = CompletableDeferred<Unit>()
        dispatch(SessionEvent.Capture(viewId, requestCameraPermission, result))
        result.await()
    }

    override fun releaseCamera(viewId: Int) {
        dispatch(SessionEvent.ReleaseCamera(viewId))
    }

    override fun resumeCamera(viewId: Int) {
        dispatch(SessionEvent.ResumeCamera(viewId))
    }

    override fun pauseCamera(viewId: Int) {
        dispatch(SessionEvent.PauseCamera(viewId))
    }

    override fun attachHostLifecycle(lifecycle: Lifecycle) {
        dispatch(SessionEvent.AttachHostLifecycle(lifecycle))
    }

    override fun detachHostLifecycle() {
        dispatch(SessionEvent.DetachHostLifecycle)
    }

    override fun onResume(owner: LifecycleOwner) {
        dispatch(SessionEvent.HostPaused(false))
    }

    override fun onPause(owner: LifecycleOwner) {
        dispatch(SessionEvent.HostPaused(true))
    }

    override suspend fun toggleFlashLight(viewId: Int) {
        if (releaseRequested) throw PluginError.CameraSessionDisposed
        val result = CompletableDeferred<Unit>()
        dispatch(SessionEvent.ToggleTorch(viewId, result))
        result.await()
    }

    override fun startScan(viewId: Int, periodMs: Int) {
        dispatch(SessionEvent.StartScan(viewId, periodMs))
    }

    override fun pauseScan(viewId: Int) {
        dispatch(SessionEvent.PauseScan(viewId))
    }

    override fun updateScanPeriod(viewId: Int, periodMs: Int) {
        dispatch(SessionEvent.UpdateScanPeriod(viewId, periodMs))
    }

    override suspend fun setZoom(viewId: Int, value: Float) {
        if (releaseRequested) throw PluginError.CameraSessionDisposed
        val result = CompletableDeferred<Unit>()
        dispatch(SessionEvent.SetZoom(viewId, value, result))
        result.await()
    }

    override fun setCropArea(viewId: Int, cropRect: RecognizeVisorCropRect) {
        dispatch(SessionEvent.SetCropArea(viewId, cropRect))
    }

    /** Routes a preview focus gesture through the session actor. */
    internal fun requestFocus(
        viewId: Int,
        resetDelayMs: Long,
        offsetX: Float,
        offsetY: Float,
    ) {
        dispatch(SessionEvent.FocusRequested(viewId, resetDelayMs, offsetX, offsetY))
    }

    /** Mirrors Flutter's native PlatformView disposal in JVM tests. */
    internal fun disposeView(viewId: Int) {
        dispatch(SessionEvent.DisposeView(viewId))
    }

    override fun release() {
        if (releaseRequested) return
        releaseRequested = true
        dispatch(SessionEvent.ReleaseSession)
    }

    /** Serial reducer. This is the only method allowed to mutate session/camera state. */
    private fun reduce(event: SessionEvent) {
        if (isReleased && event !== SessionEvent.ReleaseSession) return
        when (event) {
            is SessionEvent.RegisterView -> onRegisterView(event)
            is SessionEvent.Capture -> onCapture(event)
            is SessionEvent.PermissionCompleted -> onPermissionCompleted(event)
            is SessionEvent.ReleaseCamera -> onReleaseCamera(event.viewId)
            is SessionEvent.ResumeCamera -> onResumeCamera(event.viewId)
            is SessionEvent.PauseCamera -> onPauseCamera(event.viewId)
            is SessionEvent.AttachHostLifecycle -> onAttachHostLifecycle(event.lifecycle)
            SessionEvent.DetachHostLifecycle -> onDetachHostLifecycle()
            is SessionEvent.HostPaused -> updateHostPaused(event.paused)
            is SessionEvent.ToggleTorch -> onToggleTorch(event)
            is SessionEvent.SetZoom -> onSetZoom(event)
            is SessionEvent.SetCropArea -> onSetCropArea(event)
            is SessionEvent.StartScan -> onStartScan(event)
            is SessionEvent.PauseScan -> onPauseScan(event.viewId)
            is SessionEvent.UpdateScanPeriod -> onUpdateScanPeriod(event)
            is SessionEvent.FocusRequested -> onFocusRequested(event)
            is SessionEvent.PreviewReady -> onPreviewReady(event)
            SessionEvent.CameraBound -> onCameraBound()
            is SessionEvent.CameraBindingFailed -> onCameraBindingFailed(event.error)
            is SessionEvent.CameraAvailabilityChanged -> onCameraAvailabilityChanged(event.value)
            is SessionEvent.OperationCompleted -> onOperationCompleted(event)
            is SessionEvent.HandoffExpired -> onHandoffExpired(event.handoff)
            is SessionEvent.DisposeView -> onDisposeView(event.viewId)
            SessionEvent.ReleaseSession -> releaseSession()
        }
    }

    private fun onRegisterView(event: SessionEvent.RegisterView) {
        check(event.viewId !in views) { "Scanner platform view ${event.viewId} already exists" }
        views[event.viewId] = ScannerViewState(
            viewId = event.viewId,
            view = event.view,
            desired = DesiredConfiguration(
                zoom = event.initialZoom,
                torchEnabled = event.initialFlashEnabled,
                cropArea = event.initialCropRect,
            ),
        )
        event.initialCropRect?.let(event.view::setCropArea)
        cancelDeferredRelease()
    }

    private fun onCapture(event: SessionEvent.Capture) {
        val viewState = views[event.viewId]
        if (viewState == null) {
            event.result.completeExceptionally(PluginError.CameraIsNotInitialized)
            return
        }

        val inheritsWarmLifecycle = handoff != null &&
            lifecycleRegistry.currentState == Lifecycle.State.RESUMED
        cancelDeferredLifecycleStop(keepLifecycleWarm = inheritsWarmLifecycle)
        val previousOwner = owner
        previousOwner?.invalidateConfiguration()
        val activation = OwnerActivation(viewState)
        owner = activation
        completeSupersededOwnerWork()

        if (previousOwner?.viewState !== viewState) {
            previousOwner?.viewState?.let {
                it.view.setScanActive(false)
                it.view.detachPreview()
            }
            attachPreview(activation)
        } else {
            viewState.view.setScanActive(false)
            if (cameraConnection.isBound) scanner.hidePreview()
        }

        val captureRequest = CaptureRequest(activation, event.result)
        viewState.captureRequests += captureRequest
        when (val initialization = viewState.initialization) {
            is ViewInitialization.Failed -> {
                handoff = null
                event.result.completeExceptionally(initialization.error)
                viewState.captureRequests -= captureRequest
            }
            ViewInitialization.Ready -> {
                handoff = null
                afterViewInitialized(viewState)
            }
            ViewInitialization.New -> startViewInitialization(viewState, event.requestCameraPermission)
            is ViewInitialization.Pending -> Unit
        }
        updateCameraLifecycle()
        applyScanState()
    }

    private fun startViewInitialization(
        viewState: ScannerViewState,
        requestCameraPermission: suspend () -> Boolean,
    ) {
        val pending = ViewInitialization.Pending()
        viewState.initialization = pending
        initializationScope.launch {
            val result = runCatching {
                if (requestCameraPermission()) Unit else throw PluginError.AuthorizationCameraError
            }
            dispatch(
                SessionEvent.PermissionCompleted(
                    viewState.viewId,
                    pending,
                    result.exceptionOrNull(),
                ),
            )
        }
    }

    private fun onPermissionCompleted(event: SessionEvent.PermissionCompleted) {
        val viewState = views[event.viewId] ?: return
        if (viewState.initialization !== event.pending) return
        if (event.error != null) {
            if (owner?.viewState === viewState) handoff = null
            viewState.initialization = ViewInitialization.Failed(event.error)
            viewState.captureRequests.toList().forEach {
                it.result.completeExceptionally(event.error)
            }
            viewState.captureRequests.clear()
            updateCameraLifecycle()
            return
        }

        viewState.initialization = ViewInitialization.Ready
        if (owner?.viewState === viewState) handoff = null
        updateCameraLifecycle()
        ensureCameraBinding()
        afterViewInitialized(viewState)
    }

    private fun afterViewInitialized(viewState: ScannerViewState) {
        ensureCameraBinding()
        completeCaptureRequestsThatDoNotNeedConfiguration()
        reconcileCamera()
    }

    private fun ensureCameraBinding() {
        if (isReleased || cameraConnection !== CameraConnection.Unbound) return
        if (views.values.none { it.initialization === ViewInitialization.Ready }) return
        cameraConnection = CameraConnection.Binding(CameraAvailabilityState.Closed)
        try {
            scanner.startCamera(
                lifecycleOwner = this,
                onAvailabilityChanged = {
                    dispatch(SessionEvent.CameraAvailabilityChanged(it))
                },
                onInit = { dispatch(SessionEvent.CameraBound) },
                onError = { dispatch(SessionEvent.CameraBindingFailed(it)) },
            )
        } catch (error: Exception) {
            dispatch(SessionEvent.CameraBindingFailed(error))
        }
    }

    private fun onCameraBound() {
        val binding = cameraConnection as? CameraConnection.Binding ?: return
        cameraConnection = CameraConnection.Bound(binding.availability)
        completeCaptureRequestsThatDoNotNeedConfiguration()
        reconcileCamera()
    }

    private fun onCameraBindingFailed(error: Exception) {
        cameraConnection = CameraConnection.Unbound
        views.values.forEach { viewState ->
            viewState.captureRequests.toList().forEach { it.result.completeExceptionally(error) }
            viewState.captureRequests.clear()
            viewState.configurationWaiters.toList().forEach { it.result.completeExceptionally(error) }
            viewState.configurationWaiters.clear()
        }
        releaseRequested = true
        releaseSession()
    }

    private fun onCameraAvailabilityChanged(availability: CameraAvailability) {
        when (availability) {
            CameraAvailability.Open -> {
                if (cameraConnection.availability !is CameraAvailabilityState.Open) {
                    cameraConnection = cameraConnection.withAvailability(CameraAvailabilityState.Open())
                    owner?.invalidateConfiguration()
                }
                reconcileCamera()
            }
            is CameraAvailability.Closed -> {
                cameraConnection = cameraConnection.withAvailability(CameraAvailabilityState.Closed)
                owner?.let { activation ->
                    activation.invalidateConfiguration()
                    val viewState = activation.viewState
                    viewState.view.unbindFocus()
                    if (cameraConnection.isBound) scanner.hidePreview()
                    availability.errorCode?.let { errorCode ->
                        val error = PluginError.CameraControlError(
                            operation = CameraControlOperation.AWAIT_OPEN,
                            viewId = viewState.viewId,
                            cause = availability.cause,
                            cameraStateErrorCode = errorCode,
                        )
                        activation.configuration = ConfigurationState.Failed(
                            open = null,
                            desired = viewState.desired,
                            previewUsable = false,
                        )
                        failCurrentOwnerWork(activation, error)
                    }
                }
                applyScanState()
            }
        }
    }

    private fun onReleaseCamera(viewId: Int) {
        if (owner?.viewState?.viewId != viewId) return
        releaseCurrentOwner(scheduleHandoff = true)
    }

    private fun releaseCurrentOwner(scheduleHandoff: Boolean) {
        val releasedOwner = owner
        owner = null
        releasedOwner?.invalidateConfiguration()
        releasedOwner?.viewState?.let { viewState ->
            viewState.view.setScanActive(false)
            viewState.view.unbindFocus()
            if (cameraConnection.isBound) scanner.hidePreview()
            viewState.view.detachPreview()
        }
        completeSupersededOwnerWork()
        if (scheduleHandoff && !hostPaused && lifecycleRegistry.currentState == Lifecycle.State.RESUMED) {
            scheduleDeferredLifecycleStop()
        } else {
            updateCameraLifecycle()
        }
        applyScanState()
    }

    private fun onResumeCamera(viewId: Int) {
        val viewState = views[viewId] ?: return
        viewState.cameraRequested = true
        val activation = owner?.takeIf { it.viewState === viewState } ?: return
        cancelDeferredLifecycleStop()
        activation.invalidateConfiguration()
        updateCameraLifecycle()
        ensureCameraBinding()
        reconcileCamera()
        applyScanState()
    }

    private fun onPauseCamera(viewId: Int) {
        val viewState = views[viewId] ?: return
        viewState.cameraRequested = false
        val activation = owner?.takeIf { it.viewState === viewState } ?: return
        cancelDeferredLifecycleStop()
        activation.invalidateConfiguration()
        viewState.view.unbindFocus()
        completeCaptureRequestsThatDoNotNeedConfiguration()
        updateCameraLifecycle()
        applyScanState()
    }

    private fun onAttachHostLifecycle(lifecycle: Lifecycle) {
        if (hostLifecycle === lifecycle) {
            updateHostPaused(!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
            return
        }
        hostLifecycle?.removeObserver(this)
        hostLifecycle = lifecycle
        updateHostPaused(!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        lifecycle.addObserver(this)
    }

    private fun onDetachHostLifecycle() {
        hostLifecycle?.removeObserver(this)
        hostLifecycle = null
        updateHostPaused(true)
    }

    private fun updateHostPaused(paused: Boolean) {
        if (hostPaused == paused) return
        hostPaused = paused
        cancelDeferredLifecycleStop()
        owner?.let { activation ->
            activation.invalidateConfiguration()
            val viewState = activation.viewState
            viewState.view.unbindFocus()
            if (paused && cameraConnection.isBound) scanner.hidePreview()
        }
        updateCameraLifecycle()
        completeCaptureRequestsThatDoNotNeedConfiguration()
        if (!paused) reconcileCamera()
        applyScanState()
    }

    private fun onSetZoom(event: SessionEvent.SetZoom) {
        val viewState = views[event.viewId]
        if (viewState == null) {
            event.result.completeExceptionally(PluginError.CameraIsNotInitialized)
            return
        }
        if (!event.value.isFinite() || event.value !in MIN_ZOOM..MAX_ZOOM) {
            event.result.completeExceptionally(PluginError.InvalidArguments)
            return
        }
        val activation = owner?.takeIf { it.viewState === viewState }
        val wasApplied = activation?.hasUsablePreview(viewState.desired) == true
        val desired = updateDesiredConfiguration(viewState) { it.copy(zoom = event.value) }
        if (!shouldAwaitConfiguration(viewState)) {
            event.result.complete(Unit)
            reconcileCamera()
            return
        }
        viewState.configurationWaiters += ConfigurationWaiter(desired, event.result)
        if (wasApplied) {
            startConfigurationPipeline(
                activation,
                ApplyStage.Zoom,
                allowTorchFallback = false,
                hidePreview = false,
                previewRemainsUsable = true,
            )
        } else {
            reconcileCamera()
        }
    }

    private fun onToggleTorch(event: SessionEvent.ToggleTorch) {
        val viewState = views[event.viewId]
        if (viewState == null) {
            event.result.completeExceptionally(PluginError.CameraIsNotInitialized)
            return
        }
        val activation = owner?.takeIf { it.viewState === viewState }
        val wasApplied = activation?.hasUsablePreview(viewState.desired) == true
        val desired = updateDesiredConfiguration(viewState) {
            it.copy(torchEnabled = it.torchEnabled != true)
        }
        if (!shouldAwaitConfiguration(viewState)) {
            event.result.complete(Unit)
            reconcileCamera()
            return
        }
        viewState.configurationWaiters += ConfigurationWaiter(desired, event.result)
        if (wasApplied) {
            startConfigurationPipeline(
                activation,
                ApplyStage.Torch,
                allowTorchFallback = false,
                hidePreview = false,
                previewRemainsUsable = true,
            )
        } else {
            reconcileCamera()
        }
    }

    private fun onFocusRequested(event: SessionEvent.FocusRequested) {
        val viewState = views[event.viewId] ?: return
        val activation = owner?.takeIf { it.viewState === viewState } ?: return
        if (!activation.hasUsablePreview(viewState.desired)) return
        updateDesiredConfiguration(viewState) {
            it.copy(focus = CameraCommand.Focus(event.resetDelayMs, event.offsetX, event.offsetY))
        }
        startConfigurationPipeline(
            activation,
            ApplyStage.Focus,
            allowTorchFallback = false,
            hidePreview = false,
            previewRemainsUsable = true,
        )
    }

    private fun onSetCropArea(event: SessionEvent.SetCropArea) {
        val viewState = views[event.viewId] ?: return
        val activation = owner?.takeIf { it.viewState === viewState }
        val wasApplied = activation?.hasUsablePreview(viewState.desired) == true
        val desired = updateDesiredConfiguration(viewState) { it.copy(cropArea = event.cropRect) }
        viewState.view.setCropArea(event.cropRect)
        if (wasApplied && canOperateCamera(activation)) {
            applyCropAndDelay(desired)
            markConfigurationApplied(activation, desired)
        } else {
            reconcileCamera()
        }
    }

    private fun onStartScan(event: SessionEvent.StartScan) {
        val viewState = views[event.viewId] ?: return
        val activation = owner?.takeIf { it.viewState === viewState }
        val wasApplied = activation?.hasUsablePreview(viewState.desired) == true
        val desired = updateDesiredConfiguration(viewState) {
            it.copy(scanPeriodMs = event.periodMs)
        }
        viewState.scanRequestedByView = true
        if (wasApplied && canOperateCamera(activation)) {
            applyCropAndDelay(desired)
            markConfigurationApplied(activation, desired)
        } else {
            reconcileCamera()
        }
        applyScanState()
    }

    private fun onPauseScan(viewId: Int) {
        val viewState = views[viewId] ?: return
        viewState.scanRequestedByView = false
        applyScanState()
    }

    private fun onUpdateScanPeriod(event: SessionEvent.UpdateScanPeriod) {
        val viewState = views[event.viewId] ?: return
        val activation = owner?.takeIf { it.viewState === viewState }
        val wasApplied = activation?.hasUsablePreview(viewState.desired) == true
        val desired = updateDesiredConfiguration(viewState) {
            it.copy(scanPeriodMs = event.periodMs)
        }
        if (wasApplied && canOperateCamera(activation)) {
            applyCropAndDelay(desired)
            markConfigurationApplied(activation, desired)
        } else {
            reconcileCamera()
        }
    }

    private fun updateDesiredConfiguration(
        viewState: ScannerViewState,
        update: (DesiredConfiguration) -> DesiredConfiguration,
    ): DesiredConfiguration {
        val desired = update(viewState.desired)
        viewState.desired = desired
        viewState.configurationWaiters.forEach { it.result.complete(Unit) }
        viewState.configurationWaiters.clear()
        owner?.takeIf { it.viewState === viewState }?.invalidateConfiguration()
        return desired
    }

    private fun shouldAwaitConfiguration(viewState: ScannerViewState): Boolean =
        owner?.viewState === viewState &&
            viewState.initialization === ViewInitialization.Ready &&
            viewState.cameraRequested &&
            !hostPaused &&
            cameraConnection.isBound

    /** Starts or advances the exact OPEN configuration order. */
    private fun reconcileCamera() {
        val activation = owner ?: return
        val open = cameraConnection.open ?: return
        if (!canOperateCamera(activation)) return
        val desired = activation.viewState.desired
        when (val configuration = activation.configuration) {
            is ConfigurationState.Applied -> if (
                configuration.open === open && configuration.desired === desired
            ) {
                completeCaptureRequestsThatDoNotNeedConfiguration()
                applyScanState()
                return
            }
            is ConfigurationState.Failed -> if (
                configuration.open === open && configuration.desired === desired
            ) {
                completeCaptureRequestsThatDoNotNeedConfiguration()
                applyScanState()
                return
            }
            is ConfigurationState.AwaitingReopen -> if (
                configuration.open === open && configuration.desired === desired
            ) return
            is ConfigurationState.Applying -> return
            ConfigurationState.Pending -> Unit
        }
        startConfigurationPipeline(
            activation,
            ApplyStage.Focus,
            allowTorchFallback = activation.viewState.hasBeenConfigured,
            hidePreview = true,
            previewRemainsUsable = false,
        )
    }

    private fun startConfigurationPipeline(
        activation: OwnerActivation,
        stage: ApplyStage,
        allowTorchFallback: Boolean,
        hidePreview: Boolean,
        previewRemainsUsable: Boolean,
    ) {
        val open = cameraConnection.open ?: return
        if (owner !== activation || !canOperateCamera(activation)) return
        val execution = ConfigurationExecution(
            activation = activation,
            open = open,
            desired = activation.viewState.desired,
            allowTorchFallback = allowTorchFallback,
            previewRemainsUsable = previewRemainsUsable,
        )
        if (hidePreview) {
            scanner.hidePreview()
            activation.viewState.view.unbindFocus()
        }
        startCameraOperation(execution, stage)
        applyScanState()
    }

    private fun startCameraOperation(
        execution: ConfigurationExecution,
        stage: ApplyStage,
    ) {
        if (!isCurrent(execution)) return
        val command = when (stage) {
            ApplyStage.Focus -> execution.desired.focus
            ApplyStage.Zoom -> CameraCommand.SetZoom(execution.desired.zoom ?: DEFAULT_ZOOM)
            ApplyStage.Torch -> CameraCommand.SetTorch(execution.desired.torchEnabled == true)
        }
        val operation = CameraOperation(execution, stage, command)
        execution.activation.configuration = ConfigurationState.Applying(operation)
        try {
            val result = when (command) {
                CameraCommand.ResetFocus -> scanner.resetFocus()
                is CameraCommand.Focus -> scanner.focusOnCenter(
                    command.resetDelayMs,
                    command.offsetX,
                    command.offsetY,
                )
                is CameraCommand.SetZoom -> scanner.setZoom(command.value)
                is CameraCommand.SetTorch -> scanner.setTorch(command.enabled)
            }
            operation.task = result
            result.invokeOnCompletion { error ->
                dispatch(SessionEvent.OperationCompleted(operation, error))
            }
        } catch (error: Exception) {
            dispatch(SessionEvent.OperationCompleted(operation, error))
        }
    }

    private fun onOperationCompleted(event: SessionEvent.OperationCompleted) {
        val operation = event.operation
        val execution = operation.execution
        val applying = execution.activation.configuration as? ConfigurationState.Applying
        if (owner !== execution.activation || applying?.operation !== operation || !isCurrent(execution)) {
            return
        }
        val viewState = execution.activation.viewState
        val error = event.error
        if (error != null) {
            if (error.isCameraOperationCanceled()) {
                execution.activation.configuration = ConfigurationState.AwaitingReopen(
                    execution.open,
                    execution.desired,
                )
                scanner.hidePreview()
                viewState.view.unbindFocus()
                applyScanState()
                return
            }
            if (
                operation.stage == ApplyStage.Torch &&
                execution.allowTorchFallback &&
                execution.desired.torchEnabled == true &&
                error === PluginError.DeviceHasNotFlash
            ) {
                updateDesiredConfiguration(viewState) { it.copy(torchEnabled = false) }
                reconcileCamera()
                return
            }
            val contextualized = contextualizeCameraError(
                operation.command.operation,
                viewState.viewId,
                error,
            )
            execution.activation.configuration = ConfigurationState.Failed(
                open = execution.open,
                desired = execution.desired,
                previewUsable = execution.previewRemainsUsable,
            )
            failCurrentOwnerWork(execution.activation, contextualized)
            applyScanState()
            return
        }

        when (operation.stage) {
            ApplyStage.Focus -> {
                applyCropAndDelay(execution.desired)
                startCameraOperation(execution, ApplyStage.Zoom)
            }
            ApplyStage.Zoom -> startCameraOperation(execution, ApplyStage.Torch)
            ApplyStage.Torch -> finishConfiguration(execution)
        }
    }

    private fun finishConfiguration(execution: ConfigurationExecution) {
        if (!isCurrent(execution)) return
        val activation = execution.activation
        try {
            activation.viewState.hasBeenConfigured = true
            activation.viewState.view.bindFocus()
            scanner.showPreview()
            markConfigurationApplied(activation, execution.desired)
        } catch (error: Exception) {
            activation.configuration = ConfigurationState.Failed(
                open = execution.open,
                desired = execution.desired,
                previewUsable = false,
            )
            failCurrentOwnerWork(activation, error)
        }
        applyScanState()
    }

    private fun applyCropAndDelay(desired: DesiredConfiguration) {
        scanner.setCropArea(desired.cropArea)
        desired.scanPeriodMs?.let(scanner::updateScanPeriod)
    }

    private fun markConfigurationApplied(
        activation: OwnerActivation,
        desired: DesiredConfiguration,
    ) {
        val open = cameraConnection.open ?: return
        if (owner !== activation || activation.viewState.desired !== desired) return
        activation.configuration = ConfigurationState.Applied(open, desired)
        completeAppliedConfiguration(activation, desired)
    }

    private fun completeAppliedConfiguration(
        activation: OwnerActivation,
        desired: DesiredConfiguration,
    ) {
        val viewState = activation.viewState
        viewState.configurationWaiters.removeAll { waiter ->
            if (waiter.desired === desired) {
                waiter.result.complete(Unit)
                true
            } else {
                false
            }
        }
        viewState.captureRequests.removeAll { request ->
            if (request.activation === activation) {
                request.result.complete(Unit)
                true
            } else {
                false
            }
        }
    }

    private fun completeCaptureRequestsThatDoNotNeedConfiguration() {
        if (!cameraConnection.isBound) return
        views.values.forEach { viewState ->
            if (viewState.initialization !== ViewInitialization.Ready) return@forEach
            viewState.captureRequests.removeAll { request ->
                val isCurrentOwner = owner === request.activation
                val needsConfiguration = isCurrentOwner && viewState.cameraRequested && !hostPaused
                if (!needsConfiguration) {
                    request.result.complete(Unit)
                    true
                } else {
                    false
                }
            }
        }
    }

    private fun completeSupersededOwnerWork() {
        views.values.forEach { viewState ->
            viewState.configurationWaiters.forEach { it.result.complete(Unit) }
            viewState.configurationWaiters.clear()
        }
        completeCaptureRequestsThatDoNotNeedConfiguration()
    }

    private fun failCurrentOwnerWork(activation: OwnerActivation, error: Throwable) {
        if (owner !== activation) return
        val viewState = activation.viewState
        viewState.configurationWaiters.forEach { it.result.completeExceptionally(error) }
        viewState.configurationWaiters.clear()
        viewState.captureRequests.removeAll { request ->
            if (request.activation === activation) {
                request.result.completeExceptionally(error)
                true
            } else {
                false
            }
        }
    }

    private fun contextualizeCameraError(
        operation: CameraControlOperation,
        viewId: Int,
        error: Throwable,
    ): Throwable = when (error) {
        is PluginError.CameraControlError -> error.contextualize(operation, viewId)
        is PluginError -> error
        else -> PluginError.CameraControlError(operation, viewId, error)
    }

    private fun Throwable.isCameraOperationCanceled(): Boolean {
        var current: Throwable? = this
        while (current != null) {
            if (current is CameraControl.OperationCanceledException) return true
            current = current.cause
        }
        return false
    }

    private val CameraCommand.operation: CameraControlOperation
        get() = when (this) {
            CameraCommand.ResetFocus, is CameraCommand.Focus -> CameraControlOperation.FOCUS
            is CameraCommand.SetZoom -> CameraControlOperation.ZOOM
            is CameraCommand.SetTorch -> CameraControlOperation.TORCH
        }

    private fun canOperateCamera(activation: OwnerActivation): Boolean =
        !isReleased &&
            owner === activation &&
            activation.viewState.initialization === ViewInitialization.Ready &&
            activation.viewState.cameraRequested &&
            !hostPaused &&
            cameraConnection.isBound

    private fun isCurrent(execution: ConfigurationExecution): Boolean =
        owner === execution.activation &&
            execution.activation.viewState.desired === execution.desired &&
            cameraConnection.open === execution.open &&
            canOperateCamera(execution.activation)

    private fun currentOwner(): ScannerViewState? = owner?.viewState

    private fun attachPreview(activation: OwnerActivation) {
        val viewState = activation.viewState
        viewState.view.attachPreview {
            dispatch(SessionEvent.PreviewReady(activation))
        }
        viewState.view.setScanActive(false)
        if (cameraConnection.isBound) scanner.hidePreview()
    }

    private fun onPreviewReady(event: SessionEvent.PreviewReady) {
        if (owner !== event.activation) return
        applyScanState()
    }

    private fun updateCameraLifecycle() {
        if (isReleased) return
        val shouldRun = currentOwner()?.let { viewState ->
            viewState.initialization === ViewInitialization.Ready &&
                viewState.cameraRequested &&
                !hostPaused
        } == true || handoff != null && !hostPaused && owner != null
        lifecycleRegistry.currentState = if (shouldRun) {
            Lifecycle.State.RESUMED
        } else {
            Lifecycle.State.CREATED
        }
    }

    private fun scheduleDeferredLifecycleStop() {
        cancelDeferredLifecycleStop()
        val pendingHandoff = Handoff()
        val stopTask = Runnable { dispatch(SessionEvent.HandoffExpired(pendingHandoff)) }
        pendingHandoff.stopTask = stopTask
        handoff = pendingHandoff
        if (!mainHandler.postDelayed(stopTask, CAMERA_HANDOFF_GRACE_PERIOD_MS)) {
            dispatch(SessionEvent.HandoffExpired(pendingHandoff))
        }
    }

    private fun cancelDeferredLifecycleStop(keepLifecycleWarm: Boolean = false) {
        handoff?.stopTask?.let(mainHandler::removeCallbacks)
        if (!keepLifecycleWarm) handoff = null
    }

    private fun onHandoffExpired(expired: Handoff) {
        if (handoff !== expired || owner != null) return
        handoff = null
        updateCameraLifecycle()
    }

    private fun applyScanState() {
        val activation = owner
        val viewState = activation?.viewState
        val shouldScan = activation?.let { current ->
            val currentView = current.viewState
            currentView.scanRequestedByView &&
                currentView.cameraRequested &&
                current.hasUsablePreview(currentView.desired) &&
                currentView.view.isPreviewReady() &&
                !hostPaused &&
                !isReleased
        } == true
        scanTargetViewId = viewState?.viewId?.takeIf { shouldScan }
        if (shouldScan) {
            scanner.resumeScan()
        } else {
            scanner.pauseScan()
            cancelPendingResultDeliveries()
        }
        viewState?.view?.setScanActive(shouldScan)
    }

    private fun enqueueScanResult(result: Barcode) {
        lateinit var delivery: Runnable
        delivery = Runnable {
            val viewId = synchronized(resultDeliveryLock) {
                if (pendingResultDeliveries.remove(delivery)) {
                    scanTargetViewId
                } else {
                    null
                }
            }
            viewId?.let { onScanResult(it, result) }
        }
        synchronized(resultDeliveryLock) {
            if (scanTargetViewId == null || releaseRequested) return
            pendingResultDeliveries += delivery
            if (!mainHandler.post(delivery)) pendingResultDeliveries -= delivery
        }
    }

    private fun cancelPendingResultDeliveries() {
        val callbacks = synchronized(resultDeliveryLock) {
            pendingResultDeliveries.toList().also { pendingResultDeliveries.clear() }
        }
        callbacks.forEach(mainHandler::removeCallbacks)
    }

    private fun onDisposeView(viewId: Int) {
        val viewState = views[viewId] ?: return
        if (owner?.viewState === viewState) releaseCurrentOwner(scheduleHandoff = true)
        views.remove(viewId)
        viewState.captureRequests.toList().forEach {
            it.result.completeExceptionally(PluginError.CameraIsNotInitialized)
        }
        viewState.configurationWaiters.toList().forEach {
            it.result.completeExceptionally(PluginError.CameraIsNotInitialized)
        }
        viewState.view.setScanActive(false)
        if (views.isEmpty()) scheduleDeferredRelease()
        applyScanState()
    }

    private fun disposePlatformView(viewId: Int) {
        disposeView(viewId)
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

    private fun releaseSession() {
        if (isReleased) return
        isReleased = true
        releaseRequested = true
        hostLifecycle?.removeObserver(this)
        hostLifecycle = null
        cancelDeferredLifecycleStop()
        cancelDeferredRelease()
        scanTargetViewId = null
        cancelPendingResultDeliveries()
        owner?.invalidateConfiguration()
        owner = null

        views.values.forEach { viewState ->
            viewState.captureRequests.toList().forEach {
                it.result.completeExceptionally(PluginError.CameraSessionDisposed)
            }
            viewState.configurationWaiters.toList().forEach {
                it.result.completeExceptionally(PluginError.CameraSessionDisposed)
            }
            viewState.view.disposeFromSession()
        }
        views.clear()
        scanSubscription?.cancel()
        scanSubscription = null
        scanner.dispose()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        onReleased()
        events.close()
        actorJob.cancel()
        initializationScope.cancel()
    }

    private fun dispatch(event: SessionEvent) {
        events.trySend(event)
    }

    private sealed interface SessionEvent {
        data class RegisterView(
            val viewId: Int,
            val view: ScannerView,
            val initialZoom: Float?,
            val initialCropRect: RecognizeVisorCropRect?,
            val initialFlashEnabled: Boolean?,
        ) : SessionEvent

        data class Capture(
            val viewId: Int,
            val requestCameraPermission: suspend () -> Boolean,
            val result: CompletableDeferred<Unit>,
        ) : SessionEvent

        data class PermissionCompleted(
            val viewId: Int,
            val pending: ViewInitialization.Pending,
            val error: Throwable?,
        ) : SessionEvent

        data class ReleaseCamera(val viewId: Int) : SessionEvent
        data class ResumeCamera(val viewId: Int) : SessionEvent
        data class PauseCamera(val viewId: Int) : SessionEvent
        data class AttachHostLifecycle(val lifecycle: Lifecycle) : SessionEvent
        data object DetachHostLifecycle : SessionEvent
        data class HostPaused(val paused: Boolean) : SessionEvent
        data class ToggleTorch(val viewId: Int, val result: CompletableDeferred<Unit>) : SessionEvent
        data class SetZoom(
            val viewId: Int,
            val value: Float,
            val result: CompletableDeferred<Unit>,
        ) : SessionEvent
        data class SetCropArea(val viewId: Int, val cropRect: RecognizeVisorCropRect) : SessionEvent
        data class StartScan(val viewId: Int, val periodMs: Int) : SessionEvent
        data class PauseScan(val viewId: Int) : SessionEvent
        data class UpdateScanPeriod(val viewId: Int, val periodMs: Int) : SessionEvent
        data class FocusRequested(
            val viewId: Int,
            val resetDelayMs: Long,
            val offsetX: Float,
            val offsetY: Float,
        ) : SessionEvent
        data class PreviewReady(val activation: OwnerActivation) : SessionEvent
        data object CameraBound : SessionEvent
        data class CameraBindingFailed(val error: Exception) : SessionEvent
        data class CameraAvailabilityChanged(val value: CameraAvailability) : SessionEvent
        data class OperationCompleted(
            val operation: CameraOperation,
            val error: Throwable?,
        ) : SessionEvent
        data class HandoffExpired(val handoff: Handoff) : SessionEvent
        data class DisposeView(val viewId: Int) : SessionEvent
        data object ReleaseSession : SessionEvent
    }

    private class CameraOperation(
        val execution: ConfigurationExecution,
        val stage: ApplyStage,
        val command: CameraCommand,
    ) {
        var task: Deferred<Unit>? = null
    }

    private class ConfigurationExecution(
        val activation: OwnerActivation,
        val open: CameraAvailabilityState.Open,
        val desired: DesiredConfiguration,
        val allowTorchFallback: Boolean,
        val previewRemainsUsable: Boolean,
    )

    private enum class ApplyStage {
        Focus,
        Zoom,
        Torch,
    }

    private class CaptureRequest(
        val activation: OwnerActivation,
        val result: CompletableDeferred<Unit>,
    )

    private class ConfigurationWaiter(
        val desired: DesiredConfiguration,
        val result: CompletableDeferred<Unit>,
    )

    private data class DesiredConfiguration(
        val focus: CameraCommand = CameraCommand.ResetFocus,
        val cropArea: RecognizeVisorCropRect? = null,
        val scanPeriodMs: Int? = null,
        val zoom: Float? = null,
        val torchEnabled: Boolean? = null,
    )

    private class ScannerViewState(
        val viewId: Int,
        val view: ScannerView,
        var desired: DesiredConfiguration,
        var cameraRequested: Boolean = true,
        var initialization: ViewInitialization = ViewInitialization.New,
        var scanRequestedByView: Boolean = false,
        var hasBeenConfigured: Boolean = false,
        val captureRequests: MutableList<CaptureRequest> = mutableListOf(),
        val configurationWaiters: MutableList<ConfigurationWaiter> = mutableListOf(),
    )

    private class OwnerActivation(
        val viewState: ScannerViewState,
        var configuration: ConfigurationState = ConfigurationState.Pending,
    )

    private sealed interface ViewInitialization {
        data object New : ViewInitialization
        class Pending : ViewInitialization
        data object Ready : ViewInitialization
        class Failed(val error: Throwable) : ViewInitialization
    }

    private sealed interface ConfigurationState {
        data object Pending : ConfigurationState
        class Applying(val operation: CameraOperation) : ConfigurationState
        class Applied(
            val open: CameraAvailabilityState.Open,
            val desired: DesiredConfiguration,
        ) : ConfigurationState
        class AwaitingReopen(
            val open: CameraAvailabilityState.Open,
            val desired: DesiredConfiguration,
        ) : ConfigurationState
        class Failed(
            val open: CameraAvailabilityState.Open?,
            val desired: DesiredConfiguration,
            val previewUsable: Boolean,
        ) : ConfigurationState
    }

    private sealed interface CameraAvailabilityState {
        data object Closed : CameraAvailabilityState
        class Open : CameraAvailabilityState
    }

    private sealed interface CameraConnection {
        data object Unbound : CameraConnection
        class Binding(val availability: CameraAvailabilityState) : CameraConnection
        class Bound(val availability: CameraAvailabilityState) : CameraConnection
    }

    private val CameraConnection.availability: CameraAvailabilityState
        get() = when (this) {
            CameraConnection.Unbound -> CameraAvailabilityState.Closed
            is CameraConnection.Binding -> availability
            is CameraConnection.Bound -> availability
        }

    private val CameraConnection.isBound: Boolean
        get() = this is CameraConnection.Bound

    private val CameraConnection.open: CameraAvailabilityState.Open?
        get() = (this as? CameraConnection.Bound)?.availability as? CameraAvailabilityState.Open

    private fun CameraConnection.withAvailability(
        availability: CameraAvailabilityState,
    ): CameraConnection = when (this) {
        CameraConnection.Unbound -> this
        is CameraConnection.Binding -> CameraConnection.Binding(availability)
        is CameraConnection.Bound -> CameraConnection.Bound(availability)
    }

    private fun OwnerActivation.invalidateConfiguration() {
        val operation = (configuration as? ConfigurationState.Applying)?.operation
        configuration = ConfigurationState.Pending
        operation?.task?.cancel()
    }

    private fun OwnerActivation.hasUsablePreview(desired: DesiredConfiguration): Boolean {
        val open = cameraConnection.open ?: return false
        return when (val state = configuration) {
            is ConfigurationState.Applied -> state.open === open && state.desired === desired
            is ConfigurationState.Applying -> state.operation.execution.let { execution ->
                execution.previewRemainsUsable &&
                    execution.open === open &&
                    execution.desired === desired
            }
            is ConfigurationState.Failed ->
                state.previewUsable && state.open === open && state.desired === desired
            else -> false
        }
    }

    private class Handoff {
        var stopTask: Runnable? = null
    }

    internal companion object {
        /** Keeps the lifecycle hot briefly while Flutter hands the camera from A to B. */
        const val CAMERA_HANDOFF_GRACE_PERIOD_MS = 180L

        /** Retains an empty scanner session while Flutter replaces its platform view. */
        const val NAVIGATION_GRACE_PERIOD_MS = 300L

        private const val DEFAULT_ZOOM = 0.0F
        private const val MIN_ZOOM = 0.0F
        private const val MAX_ZOOM = 1.0F
    }
}
