package com.dns_technologies.mlkit_scanner.scanner.components.camera.x

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Size
import android.view.View
import android.view.ViewGroup
import androidx.camera.core.Camera as AndroidXCamera
import androidx.camera.core.CameraState as AndroidXCameraState
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.TorchState
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import com.dns_technologies.mlkit_scanner.PluginError
import com.dns_technologies.mlkit_scanner.scanner.components.camera.Camera
import com.dns_technologies.mlkit_scanner.scanner.components.camera.OnCameraFrame
import com.dns_technologies.mlkit_scanner.scanner.components.camera.OnError
import com.dns_technologies.mlkit_scanner.scanner.components.camera.OnInit
import com.dns_technologies.mlkit_scanner.scanner.utils.ImageProxyNv21Converter
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred

/**
 * CameraX adapter that hides CameraX APIs behind [Camera].
 *
 * @property context Android context used to create PreviewView and obtain CameraX services.
 */
class XCamera(
    private val context: Context,
) : Camera {
    private val nv21Converter = ImageProxyNv21Converter()
    private val mainExecutor = ContextCompat.getMainExecutor(context)
    private val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

    /** CameraX preview view rendered inside the scanner platform view. */
    private val cameraPreviewView = PreviewView(context).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        scaleType = PreviewView.ScaleType.FILL_CENTER
    }

    override val previewView: View = cameraPreviewView

    /** CameraX provider used to bind and unbind. */
    private var cameraProvider: ProcessCameraProvider? = null

    /** Current lifecycle state and resources owned by this adapter. */
    private var cameraState: CameraState = CameraState.Idle

    /** Latest native preview bounds used to map analysis onto the resized preview. */
    @Volatile
    private var previewSize = PreviewSize(0, 0)

    private val previewLayoutChangeListener =
        View.OnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            previewSize = PreviewSize(right - left, bottom - top)
            bindForCurrentViewPort()
        }
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit

        override fun onDisplayRemoved(displayId: Int) = Unit

        override fun onDisplayChanged(displayId: Int) {
            if (cameraPreviewView.display?.displayId == displayId) bindForCurrentViewPort()
        }
    }

    init {
        cameraPreviewView.addOnLayoutChangeListener(previewLayoutChangeListener)
        displayManager.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
    }

    /** Starts CameraX use cases while keeping preview hidden until [showPreview] is called. */
    override fun start(
        lifecycleOwner: LifecycleOwner,
        analysisExecutor: ExecutorService,
        onFrame: OnCameraFrame,
        onInit: OnInit,
        onError: OnError,
    ) {
        when (cameraState) {
            CameraState.Disposed -> throw PluginError.CameraSessionDisposed
            is PendingStart, is BoundCamera -> return
            CameraState.Idle -> Unit
        }

        val request = PendingStart(lifecycleOwner, analysisExecutor, onFrame, onInit, onError)
        cameraState = request
        previewView.alpha = 0.0F
        try {
            val providerFuture = ProcessCameraProvider.getInstance(context)
            providerFuture.addListener({
                if (cameraState !== request) return@addListener
                try {
                    cameraProvider = providerFuture.get()
                    bindForCurrentViewPort()
                } catch (error: Exception) {
                    failPendingStart(request, error)
                }
            }, mainExecutor)
        } catch (error: Exception) {
            failPendingStart(request, error)
        }
    }

    /** Returns true when a CameraX camera is currently bound. */
    override fun isActive(): Boolean = cameraState is BoundCamera

    /** Toggles torch state for the active CameraX camera. */
    override fun toggleFlashLight(): Deferred<Unit> {
        val current = cameraState as? BoundCamera
            ?: throw PluginError.CameraIsNotInitialized
        if (!current.camera.cameraInfo.hasFlashUnit()) {
            throw PluginError.DeviceHasNotFlash
        }

        return executeTorchOperation(
            current,
            current.controlState.beginTorchToggle(
                current.camera.cameraInfo.torchState.value == TorchState.ON,
            ),
        )
    }

    /** Starts CameraX focus and metering around the provided preview offsets. */
    override fun focusOnCenter(
        resetDelayMs: Long,
        offsetX: Float,
        offsetY: Float,
    ): Deferred<Unit> {
        val activeCamera = (cameraState as? BoundCamera)?.camera
            ?: throw PluginError.CameraIsNotInitialized
        if (cameraPreviewView.width == 0 || cameraPreviewView.height == 0) {
            return CompletableDeferred(Unit)
        }

        val focusPoint = cameraPreviewView.meteringPointFactory.createPoint(
            cameraPreviewView.width / 2F + offsetX,
            cameraPreviewView.height / 2F + offsetY,
        )
        val focusActionBuilder = FocusMeteringAction.Builder(
            focusPoint,
            FocusMeteringAction.FLAG_AF,
        )
        if (resetDelayMs > 0L) {
            focusActionBuilder.setAutoCancelDuration(resetDelayMs, TimeUnit.MILLISECONDS)
        } else {
            focusActionBuilder.disableAutoCancel()
        }

        return activeCamera.cameraControl.startFocusAndMetering(focusActionBuilder.build())
            .asCameraControlDeferred(mainExecutor)
    }

    /** Applies normalized linear zoom and completes after CameraX accepts the value. */
    override fun setZoom(value: Float): Deferred<Unit> {
        val current = cameraState as? BoundCamera
            ?: throw PluginError.CameraIsNotInitialized
        if (!value.isFinite() || value !in MIN_LINEAR_ZOOM..MAX_LINEAR_ZOOM) {
            throw PluginError.InvalidArguments
        }

        return executeZoomOperation(current, current.controlState.beginZoom(value))
    }

    /** Reveals preview only after session-level startup configuration is complete. */
    override fun showPreview() {
        if (!isActive()) throw PluginError.CameraIsNotInitialized
        previewView.alpha = 1.0F
    }

    /** Releases CameraX bindings owned by this adapter. */
    override fun dispose() {
        if (cameraState === CameraState.Disposed) return
        val current = cameraState as? BoundCamera
        cameraState = CameraState.Disposed
        previewView.alpha = 0.0F
        current?.let(::unbindCamera)
        cameraPreviewView.removeOnLayoutChangeListener(previewLayoutChangeListener)
        displayManager.unregisterDisplayListener(displayListener)
        nv21Converter.dispose()
        cameraProvider = null
    }

    /** Completes startup or refreshes use cases when preview geometry changes. */
    private fun bindForCurrentViewPort() {
        val provider = cameraProvider ?: return
        val viewPort = cameraPreviewView.viewPort ?: return
        when (val current = cameraState) {
            is PendingStart -> {
                try {
                    val bound = bindCamera(
                        provider,
                        current.lifecycleOwner,
                        current.analysisExecutor,
                        current.onFrame,
                        viewPort,
                    )
                    cameraState = bound
                    observeCameraState(bound)
                    try {
                        current.onInit()
                    } catch (error: Exception) {
                        cameraState = CameraState.Idle
                        previewView.alpha = 0.0F
                        unbindCamera(bound)
                        current.onError(error)
                    }
                } catch (error: Exception) {
                    failPendingStart(current, error)
                }
            }

            is BoundCamera -> if (current.viewPort.requiresRebind(viewPort)) {
                rebindCamera(provider, current, viewPort)
            }

            CameraState.Idle, CameraState.Disposed -> Unit
        }
    }

    /** Binds preview and analysis with one viewport so both expose the same field of view. */
    private fun bindCamera(
        provider: ProcessCameraProvider,
        lifecycleOwner: LifecycleOwner,
        analysisExecutor: ExecutorService,
        onFrame: OnCameraFrame,
        viewPort: ViewPort,
        controlState: CameraControlState = CameraControlState(),
    ): BoundCamera {
        val preview = createPreview(viewPort.rotation)
        val imageAnalysis = createImageAnalysis(
            analysisExecutor,
            onFrame,
            viewPort.rotation,
        )
        val useCaseGroup = UseCaseGroup.Builder()
            .setViewPort(viewPort)
            .addUseCase(preview)
            .addUseCase(imageAnalysis)
            .build()

        return try {
            val camera = provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                useCaseGroup,
            )
            BoundCamera(
                lifecycleOwner = lifecycleOwner,
                analysisExecutor = analysisExecutor,
                onFrame = onFrame,
                camera = camera,
                preview = preview,
                imageAnalysis = imageAnalysis,
                useCaseGroup = useCaseGroup,
                viewPort = viewPort,
                controlState = controlState,
                cameraStateObserver = createCameraStateObserver(camera, controlState),
            )
        } catch (error: Exception) {
            imageAnalysis.clearAnalyzer()
            throw error
        }
    }

    /** Rebinds for a new viewport and restores the prior group if CameraX rejects the change. */
    private fun rebindCamera(
        provider: ProcessCameraProvider,
        current: BoundCamera,
        viewPort: ViewPort,
    ) {
        stopObservingCameraState(current)
        current.controlState.onCameraUnavailable()
        try {
            provider.unbind(current.preview, current.imageAnalysis)
        } catch (error: Exception) {
            observeCameraState(current)
            Log.w(TAG, "Unable to unbind CameraX use cases for viewport update", error)
            return
        }

        try {
            val replacement = bindCamera(
                provider,
                current.lifecycleOwner,
                current.analysisExecutor,
                current.onFrame,
                viewPort,
                current.controlState,
            )
            cameraState = replacement
            observeCameraState(replacement)
            current.imageAnalysis.clearAnalyzer()
        } catch (error: Exception) {
            try {
                val restoredCamera = provider.bindToLifecycle(
                    current.lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    current.useCaseGroup,
                )
                val restored = current.copy(
                    camera = restoredCamera,
                    cameraStateObserver = createCameraStateObserver(
                        restoredCamera,
                        current.controlState,
                    ),
                )
                cameraState = restored
                observeCameraState(restored)
            } catch (restoreError: Exception) {
                current.imageAnalysis.clearAnalyzer()
                current.controlState.dispose()
                cameraState = CameraState.Idle
                previewView.alpha = 0.0F
                Log.e(TAG, "Unable to restore CameraX use cases after viewport update", restoreError)
            }
            Log.w(TAG, "Unable to update CameraX viewport", error)
        }
    }

    /** Creates the CameraX preview. */
    private fun createPreview(targetRotation: Int): Preview = Preview.Builder()
        .setTargetRotation(targetRotation)
        .setResolutionSelector(DEFAULT_RESOLUTION_SELECTOR)
        .build()
        .also {
            it.surfaceProvider = cameraPreviewView.surfaceProvider
        }

    /** Creates the CameraX image analysis. */
    private fun createImageAnalysis(
        analysisExecutor: ExecutorService,
        onFrame: OnCameraFrame,
        targetRotation: Int,
    ): ImageAnalysis = ImageAnalysis.Builder()
        .setTargetRotation(targetRotation)
        .setResolutionSelector(DEFAULT_RESOLUTION_SELECTOR)
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .build()
        .also { imageAnalysis ->
            imageAnalysis.setAnalyzer(analysisExecutor) { image ->
                var frame: XCameraFrame? = null
                try {
                    val currentPreviewSize = previewSize
                    frame = XCameraFrame(
                        imageProxy = image,
                        nv21Converter = nv21Converter,
                        previewWidth = currentPreviewSize.width,
                        previewHeight = currentPreviewSize.height,
                    )
                    onFrame.invoke(frame)
                } catch (error: Exception) {
                    Log.e(TAG, "Camera frame processing failed", error)
                } finally {
                    frame?.close() ?: image.close()
                }
            }
        }

    /** Unbinds the use cases owned by one completed binding. */
    private fun unbindCamera(current: BoundCamera) {
        stopObservingCameraState(current)
        current.controlState.dispose()
        try {
            cameraProvider?.unbind(current.preview, current.imageAnalysis)
        } catch (error: Exception) {
            Log.w(TAG, "Unable to unbind CameraX use cases", error)
        } finally {
            current.imageAnalysis.clearAnalyzer()
        }
    }

    private fun observeCameraState(current: BoundCamera) {
        current.camera.cameraInfo.cameraState.observeForever(current.cameraStateObserver)
    }

    private fun stopObservingCameraState(current: BoundCamera) {
        current.camera.cameraInfo.cameraState.removeObserver(current.cameraStateObserver)
    }

    private fun createCameraStateObserver(
        camera: AndroidXCamera,
        controlState: CameraControlState,
    ) = Observer<AndroidXCameraState> { state ->
        onCameraStateChanged(camera, controlState, state)
    }

    private fun onCameraStateChanged(
        sourceCamera: AndroidXCamera,
        controlState: CameraControlState,
        state: AndroidXCameraState?,
    ) {
        val current = cameraState as? BoundCamera ?: return
        if (current.camera !== sourceCamera || current.controlState !== controlState) return
        if (state?.type == AndroidXCameraState.Type.OPEN) {
            if (controlState.onCameraOpened()) restoreCameraControls(current, force = true)
        } else {
            controlState.onCameraUnavailable()
        }
    }

    private fun restoreCameraControls(current: BoundCamera, force: Boolean = false) {
        if (cameraState !== current) return
        current.controlState.beginZoomRestoration(
            current.camera.cameraInfo.zoomState.value?.linearZoom,
            force,
        )?.let { operation ->
            try {
                executeZoomOperation(current, operation)
            } catch (_: Exception) {
                // executeZoomOperation already records and logs restoration failure.
            }
        }
        current.controlState.beginTorchRestoration(
            current.camera.cameraInfo.torchState.value?.let { it == TorchState.ON },
            force,
        )?.let { operation ->
            try {
                executeTorchOperation(current, operation)
            } catch (_: Exception) {
                // executeTorchOperation already records and logs restoration failure.
            }
        }
    }

    private fun restoreCameraControls(controlState: CameraControlState) {
        val current = cameraState as? BoundCamera ?: return
        if (current.controlState === controlState) restoreCameraControls(current)
    }

    private fun executeZoomOperation(
        current: BoundCamera,
        operation: CameraControlState.Operation<Float>,
    ): Deferred<Unit> = try {
        current.camera.cameraControl.setLinearZoom(operation.value)
            .asCameraControlDeferred(mainExecutor)
            .also { result ->
                result.invokeOnCompletion { error ->
                    handleZoomCompletion(current.controlState, operation, error)
                }
            }
    } catch (error: Exception) {
        handleZoomCompletion(current.controlState, operation, error)
        throw error
    }

    private fun handleZoomCompletion(
        controlState: CameraControlState,
        operation: CameraControlState.Operation<Float>,
        error: Throwable?,
    ) {
        val completion = controlState.completeZoom(operation, error == null)
        if (completion.restorationFailed) Log.d(TAG, "Unable to restore CameraX zoom", error)
        if (completion.shouldRestore) restoreCameraControls(controlState)
    }

    private fun executeTorchOperation(
        current: BoundCamera,
        operation: CameraControlState.Operation<Boolean>,
    ): Deferred<Unit> = try {
        current.camera.cameraControl.enableTorch(operation.value)
            .asCameraControlDeferred(mainExecutor)
            .also { result ->
                result.invokeOnCompletion { error ->
                    handleTorchCompletion(current.controlState, operation, error)
                }
            }
    } catch (error: Exception) {
        handleTorchCompletion(current.controlState, operation, error)
        throw error
    }

    private fun handleTorchCompletion(
        controlState: CameraControlState,
        operation: CameraControlState.Operation<Boolean>,
        error: Throwable?,
    ) {
        val completion = controlState.completeTorch(operation, error == null)
        if (completion.restorationFailed) Log.d(TAG, "Unable to restore CameraX torch", error)
        if (completion.shouldRestore) restoreCameraControls(controlState)
    }

    private fun failPendingStart(request: PendingStart, error: Exception) {
        if (cameraState !== request) return
        cameraState = CameraState.Idle
        request.onError(error)
    }

    private sealed interface CameraState {
        object Idle : CameraState

        object Disposed : CameraState
    }

    private class PendingStart(
        val lifecycleOwner: LifecycleOwner,
        val analysisExecutor: ExecutorService,
        val onFrame: OnCameraFrame,
        val onInit: OnInit,
        val onError: OnError,
    ) : CameraState

    private data class BoundCamera(
        val lifecycleOwner: LifecycleOwner,
        val analysisExecutor: ExecutorService,
        val onFrame: OnCameraFrame,
        val camera: AndroidXCamera,
        val preview: Preview,
        val imageAnalysis: ImageAnalysis,
        val useCaseGroup: UseCaseGroup,
        val viewPort: ViewPort,
        val controlState: CameraControlState,
        val cameraStateObserver: Observer<AndroidXCameraState>,
    ) : CameraState

    /** PreviewView handles size changes; only transform changes require a disruptive rebind. */
    private fun ViewPort.requiresRebind(other: ViewPort): Boolean =
        rotation != other.rotation ||
            scaleType != other.scaleType ||
            layoutDirection != other.layoutDirection

    private data class PreviewSize(
        val width: Int,
        val height: Int,
    )

    private companion object {
        const val MIN_LINEAR_ZOOM = 0.0F
        const val MAX_LINEAR_ZOOM = 1.0F
        const val TAG = "MlkitScannerCamera"

        /** Default CameraX target resolution used by preview and analysis. */
        private val DEFAULT_TARGET_RESOLUTION = Size(720, 1280)
        private val DEFAULT_RESOLUTION_SELECTOR = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    DEFAULT_TARGET_RESOLUTION,
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                )
            )
            .build()
    }
}
