package com.dns_technologies.mlkit_scanner.session

import android.content.Context
import android.os.Handler
import android.util.Log
import androidx.annotation.AnyThread
import androidx.annotation.MainThread
import androidx.camera.core.CameraControl
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.dns_technologies.mlkit_scanner.CameraControlOperation
import com.dns_technologies.mlkit_scanner.PluginConstants
import com.dns_technologies.mlkit_scanner.PluginError
import com.dns_technologies.mlkit_scanner.scanner.ScanResultSubscription
import com.dns_technologies.mlkit_scanner.scanner.Scanner
import com.dns_technologies.mlkit_scanner.scanner.ScannerView
import com.dns_technologies.mlkit_scanner.scanner.components.camera.CameraAvailability
import com.dns_technologies.mlkit_scanner.scanner.models.Barcode
import com.dns_technologies.mlkit_scanner.scanner.models.RecognizeVisorCropRect
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus

/**
 * Owns the desired scanner configuration and every operation submitted to the shared camera.
 *
 * View/configuration state is reduced by one actor on the supplied main scope. Each view retains
 * its desired configuration, while only the current [OwnerActivation] may apply it. Camera
 * callbacks only enqueue facts; they never restore controls themselves. The supplied scope is
 * owned by this session and must dispatch on the main thread. Unexpected reducer failures end
 * the session: continuing from partially applied state would be unsafe.
 */
@MainThread
internal class ScannerSessionImpl(
    private val scanner: Scanner,
    private val mainHandler: Handler,
    onScanResult: (Int, Barcode) -> Unit,
    onReleaseRequested: (ScannerSession) -> Unit,
    private val initializationScope: CoroutineScope = MainScope() + CoroutineExceptionHandler { _, error ->
        Log.e(PluginConstants.LOG_TAG, "Scanner session failed", error)
    },
    lifecycleRegistryFactory: (LifecycleOwner) -> LifecycleRegistry = ::LifecycleRegistry,
) : ScannerSession, LifecycleOwner {
    private val lifecycleRegistry = lifecycleRegistryFactory(this)
    private var onScanResult: ((Int, Barcode) -> Unit)? = onScanResult
    private var onReleaseRequested: ((ScannerSession) -> Unit)? = onReleaseRequested
    // Prompt coroutine cancellation can retract an event already taken from the channel.
    // Retain it for main-thread cleanup in the actor's finally, never clean up UI on its sender.
    private val undeliveredEvents = ConcurrentLinkedQueue<SessionEvent>()
    private val events = Channel<SessionEvent>(
        Channel.UNLIMITED,
        onUndeliveredElement = { undeliveredEvents.add(it) },
    )
    private val views = mutableMapOf<Int, ScannerViewState>()
    private val pendingResultDeliveries = mutableSetOf<Runnable>()
    private val resultDeliveryLock = Any()

    private var owner: OwnerActivation? = null
    private var cameraConnection = CameraConnection.Unbound
    private var cameraOpen = false
    private var handoff: Handoff? = null
    private var deferredRelease: Runnable? = null
    private var isActive = false

    @Volatile
    private var scanTargetViewId: Int? = null

    @Volatile
    private var releaseRequested = false

    private var isReleased = false
    private var scanSubscription: ScanResultSubscription? =
        scanner.subscribeToScanResults(::enqueueScanResult)

    init {
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        initializationScope.launch(start = CoroutineStart.UNDISPATCHED) {
            var failure: Throwable? = null
            try {
                for (event in events) {
                    try {
                        reduce(event)
                    } catch (error: Exception) {
                        rejectEvent(event, error)
                        throw error
                    }
                }
            } catch (error: Throwable) {
                failure = error.takeUnless { it is CancellationException }
                throw error
            } finally {
                // Cancellation and reducer failures must not leave requests or resources behind.
                try {
                    releaseSession(failure ?: PluginError.CameraSessionDisposed)
                } catch (cleanupError: Exception) {
                    if (failure == null) throw cleanupError
                    if (failure !== cleanupError) failure.addSuppressed(cleanupError)
                }
            }
        }
    }

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override fun createView(
        context: Context,
        viewId: Int,
        initialZoomRatio: Double?,
        initialCropRect: RecognizeVisorCropRect?,
        initialFlashEnabled: Boolean?,
    ): ScannerView {
        check(!releaseRequested) { "Cannot add a scanner view to a released session" }
        lateinit var view: ScannerView
        view = ScannerView(
            context = context,
            preview = scanner.previewView,
            onFocusRequest = { resetDelayMs, offsetX, offsetY ->
                requestFocus(viewId, resetDelayMs, offsetX, offsetY)
            },
            onDispose = { disposeView(viewId, view) },
        )
        registerView(viewId, view, initialZoomRatio, initialCropRect, initialFlashEnabled)
        return view
    }

    /** Attaches an already-created view; exposed to keep JVM tests Android-free. */
    internal fun attachView(
        viewId: Int,
        view: ScannerView,
        initialZoomRatio: Double? = null,
        initialCropRect: RecognizeVisorCropRect? = null,
        initialFlashEnabled: Boolean? = null,
    ) {
        check(!releaseRequested) { "Cannot add a scanner view to a released session" }
        registerView(viewId, view, initialZoomRatio, initialCropRect, initialFlashEnabled)
    }

    private fun registerView(
        viewId: Int,
        view: ScannerView,
        initialZoomRatio: Double?,
        initialCropRect: RecognizeVisorCropRect?,
        initialFlashEnabled: Boolean?,
    ) {
        val registration = SessionEvent.RegisterView(
            viewId,
            view,
            initialZoomRatio?.toFloat(),
            initialCropRect,
            initialFlashEnabled,
        )
        // UI ownership is transferred only on successful submission, always from the main thread.
        if (events.trySend(registration).isFailure) view.release()
    }

    override suspend fun captureCamera(
        viewId: Int,
        requestCameraPermission: suspend () -> Boolean,
    ) {
        if (releaseRequested) return
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

    override fun activate() {
        dispatch(SessionEvent.Activate)
    }

    override fun deactivate() {
        dispatch(SessionEvent.Deactivate)
    }

    override suspend fun toggleFlashLight(viewId: Int) {
        if (releaseRequested) return
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

    override suspend fun setZoomRatio(viewId: Int, value: Float) {
        if (releaseRequested) return
        val result = CompletableDeferred<Unit>()
        dispatch(SessionEvent.SetZoomRatio(viewId, value, result))
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
    internal fun disposeView(viewId: Int, view: ScannerView? = null) {
        dispatch(SessionEvent.DisposeView(viewId, view))
    }

    override fun release() {
        if (releaseRequested) return
        try {
            markReleaseRequested()
        } finally {
            dispatch(SessionEvent.ReleaseSession)
        }
    }

    private fun markReleaseRequested() {
        if (releaseRequested) return
        releaseRequested = true
        val callback = onReleaseRequested
        onReleaseRequested = null
        callback?.invoke(this)
    }

    /** Processes one event at a time; callbacks can only enqueue subsequent events. */
    private fun reduce(event: SessionEvent) {
        if (releaseRequested && event !== SessionEvent.ReleaseSession) {
            rejectEvent(event, PluginError.CameraSessionDisposed)
            return
        }
        when (event) {
            is SessionEvent.RegisterView -> onRegisterView(event)
            is SessionEvent.Capture -> onCapture(event)
            is SessionEvent.PermissionCompleted -> onPermissionCompleted(event)
            is SessionEvent.ReleaseCamera -> onReleaseCamera(event.viewId)
            is SessionEvent.ResumeCamera -> onResumeCamera(event.viewId)
            is SessionEvent.PauseCamera -> onPauseCamera(event.viewId)
            SessionEvent.Activate -> updateActiveState(true)
            SessionEvent.Deactivate -> updateActiveState(false)
            is SessionEvent.ToggleTorch -> onToggleTorch(event)
            is SessionEvent.SetZoomRatio -> onSetZoomRatio(event)
            is SessionEvent.SetCropArea -> onSetCropArea(event)
            is SessionEvent.StartScan -> onStartScan(event)
            is SessionEvent.PauseScan -> onPauseScan(event.viewId)
            is SessionEvent.UpdateScanPeriod -> onUpdateScanPeriod(event)
            is SessionEvent.FocusRequested -> onFocusRequested(event)
            is SessionEvent.PreviewReady -> onPreviewReady(event)
            SessionEvent.CameraBound -> onCameraBound()
            is SessionEvent.CameraBindingFailed -> releaseSession(event.error)
            is SessionEvent.CameraAvailabilityChanged -> onCameraAvailabilityChanged(event.value)
            is SessionEvent.OperationCompleted -> onOperationCompleted(event)
            is SessionEvent.HandoffExpired -> onHandoffExpired(event.handoff)
            is SessionEvent.DeferredReleaseExpired -> onDeferredReleaseExpired(event.task)
            is SessionEvent.DisposeView -> onDisposeView(event)
            SessionEvent.ReleaseSession -> releaseSession()
        }
    }

    private fun onRegisterView(event: SessionEvent.RegisterView) {
        check(event.viewId !in views) { "Scanner platform view ${event.viewId} already exists" }
        views[event.viewId] = ScannerViewState(
            viewId = event.viewId,
            view = event.view,
            desired = DesiredConfiguration(
                zoomRatio = event.initialZoomRatio,
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
            event.result.complete(Unit)
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
            if (isCameraBound) scanner.hidePreview()
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
                afterViewInitialized()
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
        val permissionTask = initializationScope.launch(start = CoroutineStart.LAZY) {
            var failure: Exception? = null
            try {
                if (requestCameraPermission()) Unit else throw PluginError.AuthorizationCameraError
            } catch (error: Exception) {
                failure = error
                if (error is CancellationException) throw error
            } finally {
                dispatch(SessionEvent.PermissionCompleted(viewState, failure))
            }
        }
        // Publish the actual cancellable work before it can complete synchronously.
        viewState.initialization = ViewInitialization.Pending(permissionTask)
        permissionTask.start()
    }

    private fun onPermissionCompleted(event: SessionEvent.PermissionCompleted) {
        val viewState = event.viewState
        if (views[viewState.viewId] !== viewState) return
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
        afterViewInitialized()
    }

    private fun afterViewInitialized() {
        ensureCameraBinding()
        completeCaptureRequestsThatDoNotNeedConfiguration()
        reconcileCamera()
    }

    private fun ensureCameraBinding() {
        if (releaseRequested || cameraConnection != CameraConnection.Unbound) return
        if (views.values.none { it.initialization === ViewInitialization.Ready }) return
        cameraConnection = CameraConnection.Binding
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
        if (cameraConnection != CameraConnection.Binding) return
        cameraConnection = CameraConnection.Bound
        completeCaptureRequestsThatDoNotNeedConfiguration()
        reconcileCamera()
    }

    private fun onCameraAvailabilityChanged(availability: CameraAvailability) {
        when (availability) {
            CameraAvailability.Open -> {
                if (!cameraOpen) {
                    cameraOpen = true
                    owner?.invalidateConfiguration()
                }
                reconcileCamera()
            }
            is CameraAvailability.Closed -> {
                cameraOpen = false
                owner?.let { activation ->
                    activation.invalidateConfiguration()
                    val viewState = activation.viewState
                    viewState.view.unbindFocus()
                    if (isCameraBound) scanner.hidePreview()
                    availability.errorCode?.let { errorCode ->
                        val error = PluginError.CameraControlError(
                            operation = CameraControlOperation.AWAIT_OPEN,
                            viewId = viewState.viewId,
                            cause = availability.cause,
                            cameraStateErrorCode = errorCode,
                        )
                        activation.configuration = ConfigurationState.Failed(
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
            if (isCameraBound) scanner.hidePreview()
            viewState.view.detachPreview()
        }
        completeSupersededOwnerWork()
        if (scheduleHandoff && isActive && lifecycleRegistry.currentState == Lifecycle.State.RESUMED) {
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

    private fun updateActiveState(active: Boolean) {
        if (isActive == active) return
        isActive = active
        cancelDeferredLifecycleStop()
        owner?.let { activation ->
            activation.invalidateConfiguration()
            val viewState = activation.viewState
            viewState.view.unbindFocus()
            if (!active && isCameraBound) scanner.hidePreview()
        }
        updateCameraLifecycle()
        completeCaptureRequestsThatDoNotNeedConfiguration()
        if (active) reconcileCamera()
        applyScanState()
    }

    private fun onSetZoomRatio(event: SessionEvent.SetZoomRatio) {
        val viewState = views[event.viewId]
        if (viewState == null) {
            event.result.complete(Unit)
            return
        }
        if (!event.value.isFinite() || event.value <= 0.0F) {
            event.result.completeExceptionally(PluginError.InvalidArguments)
            return
        }
        updateCameraControl(
            viewState,
            viewState.desired.copy(zoomRatio = event.value),
            CameraCommand.SetZoomRatio(event.value),
            event.result,
        )
    }

    private fun onToggleTorch(event: SessionEvent.ToggleTorch) {
        val viewState = views[event.viewId]
        if (viewState == null) {
            event.result.complete(Unit)
            return
        }
        val enabled = viewState.desired.torchEnabled != true
        updateCameraControl(
            viewState,
            viewState.desired.copy(torchEnabled = enabled),
            CameraCommand.SetTorch(enabled),
            event.result,
        )
    }

    private fun updateCameraControl(
        viewState: ScannerViewState,
        desired: DesiredConfiguration,
        command: CameraCommand,
        result: CompletableDeferred<Unit>,
    ) {
        val activation = owner?.takeIf { it.viewState === viewState }
        val wasApplied = activation?.hasAppliedConfiguration() == true
        updateDesiredConfiguration(viewState, desired)
        if (activation == null || !canOperateCamera(activation)) {
            result.complete(Unit)
            reconcileCamera()
            return
        }
        viewState.configurationWaiters += result
        if (wasApplied) {
            startSingleCameraOperation(activation, command)
        } else {
            reconcileCamera()
        }
    }

    private fun onFocusRequested(event: SessionEvent.FocusRequested) {
        val viewState = views[event.viewId] ?: return
        val activation = owner?.takeIf { it.viewState === viewState } ?: return
        // Focus is transient: do not let a gesture discard a retained zoom/torch still applying.
        val pendingCommand = (activation.configuration as? ConfigurationState.Applying)?.operation?.command
        if (!activation.hasAppliedConfiguration() && pendingCommand !is CameraCommand.Focus) return
        activation.invalidateConfiguration()
        startSingleCameraOperation(
            activation = activation,
            command = CameraCommand.Focus(event.resetDelayMs, event.offsetX, event.offsetY),
        )
    }

    private fun onSetCropArea(event: SessionEvent.SetCropArea) {
        val viewState = views[event.viewId] ?: return
        val activation = owner?.takeIf { it.viewState === viewState }
        val wasApplied = activation?.hasAppliedConfiguration() == true
        val desired = viewState.desired.copy(cropArea = event.cropRect)
        updateDesiredConfiguration(viewState, desired)
        viewState.view.setCropArea(event.cropRect)
        if (wasApplied && canOperateCamera(activation)) {
            scanner.setCropArea(desired.cropArea)
            markConfigurationApplied(activation, desired)
        } else {
            reconcileCamera()
        }
    }

    private fun onStartScan(event: SessionEvent.StartScan) {
        val viewState = views[event.viewId] ?: return
        viewState.scanRequestedByView = true
        updateScanPeriod(viewState, event.periodMs)
        applyScanState()
    }

    private fun onPauseScan(viewId: Int) {
        val viewState = views[viewId] ?: return
        viewState.scanRequestedByView = false
        applyScanState()
    }

    private fun onUpdateScanPeriod(event: SessionEvent.UpdateScanPeriod) {
        val viewState = views[event.viewId] ?: return
        updateScanPeriod(viewState, event.periodMs)
    }

    private fun updateScanPeriod(viewState: ScannerViewState, periodMs: Int) {
        val activation = owner?.takeIf { it.viewState === viewState }
        val wasApplied = activation?.hasAppliedConfiguration() == true
        val desired = viewState.desired.copy(scanPeriodMs = periodMs)
        updateDesiredConfiguration(viewState, desired)
        if (wasApplied && canOperateCamera(activation)) {
            scanner.updateScanPeriod(periodMs)
            markConfigurationApplied(activation, desired)
        } else {
            reconcileCamera()
        }
    }

    private fun updateDesiredConfiguration(
        viewState: ScannerViewState,
        desired: DesiredConfiguration,
    ) {
        viewState.desired = desired
        viewState.configurationWaiters.forEach { it.complete(Unit) }
        viewState.configurationWaiters.clear()
        owner?.takeIf { it.viewState === viewState }?.invalidateConfiguration()
    }

    /** Starts or advances the exact OPEN configuration order. */
    private fun reconcileCamera() {
        val activation = owner ?: return
        if (!cameraOpen || !canOperateCamera(activation)) return
        when (activation.configuration) {
            ConfigurationState.Applied, is ConfigurationState.Failed -> {
                completeCaptureRequestsThatDoNotNeedConfiguration()
                applyScanState()
                return
            }
            ConfigurationState.AwaitingReopen, is ConfigurationState.Applying -> return
            ConfigurationState.Pending -> Unit
        }
        startConfigurationPipeline(activation)
    }

    private fun startConfigurationPipeline(activation: OwnerActivation) {
        val execution = StartupConfigurationExecution(
            activation = activation,
            desired = activation.viewState.desired,
            allowTorchFallback = activation.viewState.hasBeenConfigured,
        )
        scanner.hidePreview()
        activation.viewState.view.unbindFocus()
        startStartupCameraOperation(execution, ApplyStage.Focus)
        applyScanState()
    }

    /** Executes one runtime command without replaying the startup configuration pipeline. */
    private fun startSingleCameraOperation(
        activation: OwnerActivation,
        command: CameraCommand,
    ) {
        if (!cameraOpen || !canOperateCamera(activation)) return
        val execution = SingleCameraExecution(
            activation = activation,
            desired = activation.viewState.desired,
        )
        startCameraOperation(execution, command)
        applyScanState()
    }

    private fun startStartupCameraOperation(
        execution: StartupConfigurationExecution,
        stage: ApplyStage,
    ) {
        startCameraOperation(execution, startupCommand(execution, stage))
    }

    private fun startCameraOperation(
        execution: CameraExecution,
        command: CameraCommand,
    ) {
        if (!isCurrent(execution)) return
        val operation = CameraOperation(execution, command)
        execution.activation.configuration = ConfigurationState.Applying(operation)
        try {
            val result = when (command) {
                CameraCommand.ResetFocus -> scanner.resetFocus()
                is CameraCommand.Focus -> scanner.focusOnCenter(
                    command.resetDelayMs,
                    command.offsetX,
                    command.offsetY,
                )
                is CameraCommand.SetZoomRatio -> scanner.setZoomRatio(command.value)
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

    private fun startupCommand(
        execution: StartupConfigurationExecution,
        stage: ApplyStage,
    ): CameraCommand = when (stage) {
        ApplyStage.Focus -> CameraCommand.ResetFocus
        ApplyStage.Zoom -> CameraCommand.SetZoomRatio(
            execution.desired.zoomRatio ?: DEFAULT_ZOOM_RATIO,
        )
        ApplyStage.Torch -> CameraCommand.SetTorch(execution.desired.torchEnabled == true)
    }

    private fun onOperationCompleted(event: SessionEvent.OperationCompleted) {
        val operation = event.operation
        val execution = operation.execution
        val applying = execution.activation.configuration as? ConfigurationState.Applying
        if (applying?.operation !== operation || !isCurrent(execution)) {
            return
        }
        val viewState = execution.activation.viewState
        val error = event.error
        if (error != null) {
            if (error.isCameraOperationCanceled()) {
                execution.activation.configuration = ConfigurationState.AwaitingReopen
                scanner.hidePreview()
                viewState.view.unbindFocus()
                applyScanState()
                return
            }
            if (
                operation.command.stage == ApplyStage.Torch &&
                execution is StartupConfigurationExecution &&
                execution.allowTorchFallback &&
                execution.desired.torchEnabled == true &&
                error === PluginError.DeviceHasNotFlash
            ) {
                updateDesiredConfiguration(viewState, viewState.desired.copy(torchEnabled = false))
                reconcileCamera()
                return
            }
            val contextualized = contextualizeCameraError(
                operation.command.operation,
                viewState.viewId,
                error,
            )
            execution.activation.configuration = if (operation.command is CameraCommand.Focus) {
                // Failed metering does not invalidate the retained zoom/torch configuration.
                ConfigurationState.Applied
            } else {
                ConfigurationState.Failed(previewUsable = execution.previewRemainsUsable)
            }
            failCurrentOwnerWork(execution.activation, contextualized)
            applyScanState()
            return
        }

        if (execution is SingleCameraExecution) {
            markConfigurationApplied(execution.activation, execution.desired)
            applyScanState()
            return
        }
        execution as StartupConfigurationExecution

        when (operation.command.stage) {
            ApplyStage.Focus -> {
                applyCropAndDelay(execution.desired)
                startStartupCameraOperation(execution, ApplyStage.Zoom)
            }
            ApplyStage.Zoom -> startStartupCameraOperation(execution, ApplyStage.Torch)
            ApplyStage.Torch -> finishConfiguration(execution)
        }
    }

    private fun finishConfiguration(execution: StartupConfigurationExecution) {
        if (!isCurrent(execution)) return
        val activation = execution.activation
        try {
            activation.viewState.hasBeenConfigured = true
            activation.viewState.view.bindFocus()
            scanner.showPreview()
            markConfigurationApplied(activation, execution.desired)
        } catch (error: Exception) {
            activation.configuration = ConfigurationState.Failed(
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
        if (!cameraOpen || !canOperateCamera(activation) || activation.viewState.desired !== desired) return
        activation.configuration = ConfigurationState.Applied
        val viewState = activation.viewState
        viewState.configurationWaiters.forEach { it.complete(Unit) }
        viewState.configurationWaiters.clear()
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
        if (!isCameraBound) return
        views.values.forEach { viewState ->
            if (viewState.initialization !== ViewInitialization.Ready) return@forEach
            viewState.captureRequests.removeAll { request ->
                val isCurrentOwner = owner === request.activation
                val needsConfiguration = isCurrentOwner && viewState.cameraRequested && isActive
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
            viewState.configurationWaiters.forEach { it.complete(Unit) }
            viewState.configurationWaiters.clear()
        }
        completeCaptureRequestsThatDoNotNeedConfiguration()
    }

    private fun failCurrentOwnerWork(activation: OwnerActivation, error: Throwable) {
        if (owner !== activation) return
        val viewState = activation.viewState
        viewState.configurationWaiters.forEach { it.completeExceptionally(error) }
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
        // Throwable causes can form a cycle; walk actual exceptions without revisiting them.
        val visited = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        var current: Throwable? = this
        while (current != null && visited.add(current)) {
            if (current is CameraControl.OperationCanceledException) return true
            current = current.cause
        }
        return false
    }

    private val CameraCommand.operation: CameraControlOperation
        get() = when (this) {
            CameraCommand.ResetFocus, is CameraCommand.Focus -> CameraControlOperation.FOCUS
            is CameraCommand.SetZoomRatio -> CameraControlOperation.ZOOM
            is CameraCommand.SetTorch -> CameraControlOperation.TORCH
        }

    private val CameraCommand.stage: ApplyStage
        get() = when (this) {
            CameraCommand.ResetFocus, is CameraCommand.Focus -> ApplyStage.Focus
            is CameraCommand.SetZoomRatio -> ApplyStage.Zoom
            is CameraCommand.SetTorch -> ApplyStage.Torch
        }

    private fun canOperateCamera(activation: OwnerActivation): Boolean =
        !releaseRequested &&
            owner === activation &&
            activation.viewState.initialization === ViewInitialization.Ready &&
            activation.viewState.cameraRequested &&
            isActive &&
            isCameraBound

    private fun isCurrent(execution: CameraExecution): Boolean =
        execution.activation.viewState.desired === execution.desired &&
            cameraOpen &&
            canOperateCamera(execution.activation)

    private fun currentOwner(): ScannerViewState? = owner?.viewState

    private fun attachPreview(activation: OwnerActivation) {
        val viewState = activation.viewState
        viewState.view.attachPreview {
            dispatch(SessionEvent.PreviewReady(activation))
        }
        viewState.view.setScanActive(false)
        if (isCameraBound) scanner.hidePreview()
    }

    private fun onPreviewReady(event: SessionEvent.PreviewReady) {
        if (owner !== event.activation) return
        applyScanState()
    }

    private fun updateCameraLifecycle() {
        if (releaseRequested) return
        val shouldRun = currentOwner()?.let { viewState ->
            viewState.initialization === ViewInitialization.Ready &&
                viewState.cameraRequested &&
                isActive
        } == true || handoff != null && isActive && owner != null
        lifecycleRegistry.currentState = if (shouldRun) {
            Lifecycle.State.RESUMED
        } else {
            Lifecycle.State.CREATED
        }
    }

    private fun scheduleDeferredLifecycleStop() {
        cancelDeferredLifecycleStop()
        val pendingHandoff = Handoff()
        handoff = pendingHandoff
        if (!mainHandler.postDelayed(pendingHandoff, CAMERA_HANDOFF_GRACE_PERIOD_MS)) {
            dispatch(SessionEvent.HandoffExpired(pendingHandoff))
        }
    }

    private fun cancelDeferredLifecycleStop(keepLifecycleWarm: Boolean = false) {
        handoff?.let(mainHandler::removeCallbacks)
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
                current.hasUsablePreview() &&
                currentView.view.isPreviewReady() &&
                isActive &&
                !releaseRequested
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

    @AnyThread
    private fun enqueueScanResult(result: Barcode) {
        lateinit var delivery: Runnable
        delivery = Runnable {
            val viewId = synchronized(resultDeliveryLock) {
                if (pendingResultDeliveries.remove(delivery) && !releaseRequested) {
                    scanTargetViewId
                } else {
                    null
                }
            }
            viewId?.let { onScanResult?.invoke(it, result) }
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

    private fun onDisposeView(event: SessionEvent.DisposeView) {
        val viewState = views[event.viewId] ?: return
        if (event.view != null && viewState.view !== event.view) return
        if (owner?.viewState === viewState) releaseCurrentOwner(scheduleHandoff = true)
        views.remove(event.viewId)
        (viewState.initialization as? ViewInitialization.Pending)?.task?.cancel()
        viewState.captureRequests.toList().forEach {
            it.result.complete(Unit)
        }
        viewState.configurationWaiters.toList().forEach {
            it.complete(Unit)
        }
        viewState.view.setScanActive(false)
        if (views.isEmpty()) scheduleDeferredRelease()
        applyScanState()
    }

    private fun scheduleDeferredRelease() {
        if (releaseRequested || deferredRelease != null) return
        val releaseTask = object : Runnable {
            override fun run() = dispatch(SessionEvent.DeferredReleaseExpired(this))
        }
        deferredRelease = releaseTask
        if (!mainHandler.postDelayed(releaseTask, NAVIGATION_GRACE_PERIOD_MS)) {
            dispatch(SessionEvent.DeferredReleaseExpired(releaseTask))
        }
    }

    private fun onDeferredReleaseExpired(task: Runnable) {
        // removeCallbacks cannot retract a callback that was already dequeued.
        if (deferredRelease !== task) return
        deferredRelease = null
        if (views.isEmpty()) release()
    }

    private fun cancelDeferredRelease() {
        deferredRelease?.let(mainHandler::removeCallbacks)
        deferredRelease = null
    }

    private fun releaseSession(cause: Throwable = PluginError.CameraSessionDisposed) {
        if (isReleased) return
        isReleased = true
        // Stop submissions before callbacks can reenter. Drain the now-finite queue below.
        events.close()
        scanTargetViewId = null
        onScanResult = null
        var failure: Exception? = null
        fun cleanup(action: () -> Unit) {
            try {
                action()
            } catch (error: Exception) {
                val first = failure
                if (first == null) failure = error
                else if (first !== error) first.addSuppressed(error)
            }
        }
        cleanup { markReleaseRequested() }
        cleanup(::cancelDeferredLifecycleStop)
        cleanup(::cancelDeferredRelease)
        cleanup(::cancelPendingResultDeliveries)
        cleanup { owner?.invalidateConfiguration() }
        owner = null
        views.values.forEach { viewState ->
            cleanup { (viewState.initialization as? ViewInitialization.Pending)?.task?.cancel() }
            viewState.captureRequests.toList().forEach {
                it.result.completeExceptionally(cause)
            }
            viewState.configurationWaiters.toList().forEach {
                it.completeExceptionally(cause)
            }
            cleanup(viewState.view::release)
        }
        views.clear()
        for (event in generateSequence { events.tryReceive().getOrNull() ?: undeliveredEvents.poll() }) {
            cleanup { rejectEvent(event, cause) }
        }
        cleanup { scanSubscription?.cancel() }
        scanSubscription = null
        cleanup(scanner::dispose)
        cleanup { lifecycleRegistry.currentState = Lifecycle.State.DESTROYED }
        cleanup { initializationScope.cancel() }
        failure?.let { throw it }
    }

    @AnyThread
    private fun dispatch(event: SessionEvent) {
        if (events.trySend(event).isFailure) {
            event.result?.completeExceptionally(PluginError.CameraSessionDisposed)
        }
    }

    /** Rejects an event owned by the actor; unregistered views are released on its main thread. */
    private fun rejectEvent(event: SessionEvent, error: Throwable) {
        event.result?.completeExceptionally(error)
        if (event is SessionEvent.RegisterView && views[event.viewId]?.view !== event.view) {
            event.view.release()
        }
    }

    private sealed interface SessionEvent {
        val result: CompletableDeferred<Unit>? get() = null

        data class RegisterView(
            val viewId: Int,
            val view: ScannerView,
            val initialZoomRatio: Float?,
            val initialCropRect: RecognizeVisorCropRect?,
            val initialFlashEnabled: Boolean?,
        ) : SessionEvent

        data class Capture(
            val viewId: Int,
            val requestCameraPermission: suspend () -> Boolean,
            override val result: CompletableDeferred<Unit>,
        ) : SessionEvent

        data class PermissionCompleted(
            val viewState: ScannerViewState,
            val error: Throwable?,
        ) : SessionEvent

        data class ReleaseCamera(val viewId: Int) : SessionEvent
        data class ResumeCamera(val viewId: Int) : SessionEvent
        data class PauseCamera(val viewId: Int) : SessionEvent
        data object Activate : SessionEvent
        data object Deactivate : SessionEvent
        data class ToggleTorch(
            val viewId: Int,
            override val result: CompletableDeferred<Unit>,
        ) : SessionEvent
        data class SetZoomRatio(
            val viewId: Int,
            val value: Float,
            override val result: CompletableDeferred<Unit>,
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
        data class DeferredReleaseExpired(val task: Runnable) : SessionEvent
        data class DisposeView(
            val viewId: Int,
            val view: ScannerView?,
        ) : SessionEvent
        data object ReleaseSession : SessionEvent
    }

    /** Describes a session operation; camera adapters expose methods, not this command model. */
    private sealed interface CameraCommand {
        data object ResetFocus : CameraCommand
        data class Focus(
            val resetDelayMs: Long,
            val offsetX: Float,
            val offsetY: Float,
        ) : CameraCommand
        data class SetZoomRatio(val value: Float) : CameraCommand
        data class SetTorch(val enabled: Boolean) : CameraCommand
    }

    private class CameraOperation(
        val execution: CameraExecution,
        val command: CameraCommand,
    ) {
        var task: Deferred<Unit>? = null
    }

    private sealed class CameraExecution(
        val activation: OwnerActivation,
        val desired: DesiredConfiguration,
        val previewRemainsUsable: Boolean,
    )

    private class StartupConfigurationExecution(
        activation: OwnerActivation,
        desired: DesiredConfiguration,
        val allowTorchFallback: Boolean,
    ) : CameraExecution(activation, desired, previewRemainsUsable = false)

    private class SingleCameraExecution(
        activation: OwnerActivation,
        desired: DesiredConfiguration,
    ) : CameraExecution(activation, desired, previewRemainsUsable = true)

    private enum class ApplyStage {
        Focus,
        Zoom,
        Torch,
    }

    private class CaptureRequest(
        val activation: OwnerActivation,
        val result: CompletableDeferred<Unit>,
    )

    private data class DesiredConfiguration(
        val cropArea: RecognizeVisorCropRect? = null,
        val scanPeriodMs: Int? = null,
        val zoomRatio: Float? = null,
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
        val configurationWaiters: MutableList<CompletableDeferred<Unit>> = mutableListOf(),
    )

    private class OwnerActivation(
        val viewState: ScannerViewState,
        var configuration: ConfigurationState = ConfigurationState.Pending,
    )

    private sealed interface ViewInitialization {
        data object New : ViewInitialization
        class Pending(val task: Job) : ViewInitialization
        data object Ready : ViewInitialization
        class Failed(val error: Throwable) : ViewInitialization
    }

    private sealed interface ConfigurationState {
        data object Pending : ConfigurationState
        class Applying(val operation: CameraOperation) : ConfigurationState
        data object Applied : ConfigurationState
        data object AwaitingReopen : ConfigurationState
        class Failed(val previewUsable: Boolean) : ConfigurationState
    }

    // Availability may arrive before binding completion. Neither state is an identity token:
    // closing the camera invalidates configuration and cancels the actual owned operation.
    private enum class CameraConnection { Unbound, Binding, Bound }

    private val isCameraBound: Boolean
        get() = cameraConnection == CameraConnection.Bound

    private fun OwnerActivation.invalidateConfiguration() {
        val operation = (configuration as? ConfigurationState.Applying)?.operation
        configuration = ConfigurationState.Pending
        operation?.task?.cancel()
    }

    private fun OwnerActivation.hasUsablePreview(): Boolean =
        cameraOpen && canOperateCamera(this) && when (val state = configuration) {
            ConfigurationState.Applied -> true
            is ConfigurationState.Applying -> state.operation.execution.previewRemainsUsable
            is ConfigurationState.Failed -> state.previewUsable
            else -> false
        }

    // A visible preview does not mean a pending zoom/torch has reached the hardware.
    // Only a fully applied configuration permits updating just one setting.
    private fun OwnerActivation.hasAppliedConfiguration(): Boolean =
        configuration === ConfigurationState.Applied && hasUsablePreview()

    private inner class Handoff : Runnable {
        override fun run() = dispatch(SessionEvent.HandoffExpired(this))
    }

    internal companion object {
        /** Keeps the lifecycle hot briefly while Flutter hands the camera from A to B. */
        const val CAMERA_HANDOFF_GRACE_PERIOD_MS = 180L

        /** Retains an empty scanner session while Flutter replaces its platform view. */
        const val NAVIGATION_GRACE_PERIOD_MS = 300L

        private const val DEFAULT_ZOOM_RATIO = 1.0F
    }
}
