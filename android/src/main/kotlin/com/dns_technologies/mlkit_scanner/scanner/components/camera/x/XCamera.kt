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
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import com.dns_technologies.mlkit_scanner.CameraControlOperation
import com.dns_technologies.mlkit_scanner.PluginError
import com.dns_technologies.mlkit_scanner.scanner.components.camera.Camera
import com.dns_technologies.mlkit_scanner.scanner.components.camera.CameraAvailability
import com.dns_technologies.mlkit_scanner.scanner.components.camera.CameraCommand
import com.dns_technologies.mlkit_scanner.scanner.components.camera.OnCameraFrame
import com.dns_technologies.mlkit_scanner.scanner.components.camera.OnCameraAvailabilityChanged
import com.dns_technologies.mlkit_scanner.scanner.components.camera.OnError
import com.dns_technologies.mlkit_scanner.scanner.components.camera.OnInit
import com.dns_technologies.mlkit_scanner.scanner.utils.ImageProxyNv21Converter
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred

/**
 * Native camera adapter exposed through [Camera].
 *
 * @property context Android context used to create the preview and obtain camera services.
 */
class XCamera(
    private val context: Context,
) : Camera {
    private val nv21Converter = ImageProxyNv21Converter()
    private val previewCropState = PreviewCropState()
    private val mainExecutor = ContextCompat.getMainExecutor(context)
    private val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

    /** Native preview rendered inside the scanner platform view. */
    private val cameraPreviewView = PreviewView(context).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        scaleType = PreviewView.ScaleType.FILL_CENTER
    }

    override val previewView: View = cameraPreviewView

    /** Camera provider used to bind and unbind resources. */
    private var cameraProvider: ProcessCameraProvider? = null

    /** Current lifecycle state and resources owned by this adapter. */
    private var bindingState: BindingState = BindingState.Idle

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

    /** Starts preview and analysis while keeping preview hidden until [showPreview] is called. */
    override fun bind(
        lifecycleOwner: LifecycleOwner,
        analysisExecutor: ExecutorService,
        onFrame: OnCameraFrame,
        onAvailabilityChanged: OnCameraAvailabilityChanged,
        onInit: OnInit,
        onError: OnError,
    ) {
        when (bindingState) {
            BindingState.Disposed -> throw PluginError.CameraSessionDisposed
            is PendingStart, is BoundCamera -> return
            BindingState.Idle -> Unit
        }

        val request = PendingStart(
            lifecycleOwner,
            analysisExecutor,
            onFrame,
            onAvailabilityChanged,
            onInit,
            onError,
        )
        bindingState = request
        previewView.alpha = 0.0F
        try {
            val providerFuture = ProcessCameraProvider.getInstance(context)
            providerFuture.addListener({
                if (bindingState !== request) return@addListener
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

    /** Returns true when a camera is currently bound. */
    override fun isBound(): Boolean = bindingState is BoundCamera

    /** Executes one command and deliberately retains no desired camera configuration. */
    override fun execute(command: CameraCommand): Deferred<Unit> = when (command) {
        CameraCommand.ResetFocus -> executeResetFocus()
        is CameraCommand.Focus -> executeFocus(command)
        is CameraCommand.SetZoomRatio -> executeZoomRatio(command.value)
        is CameraCommand.SetTorch -> executeTorch(command.enabled)
    }

    /** Starts focus and metering around the provided preview offsets. */
    private fun executeFocus(command: CameraCommand.Focus): Deferred<Unit> {
        val activeCamera = (bindingState as? BoundCamera)?.camera
            ?: throw PluginError.CameraIsNotInitialized
        if (cameraPreviewView.width == 0 || cameraPreviewView.height == 0) {
            return CompletableDeferred(Unit)
        }

        val focusPoint = cameraPreviewView.meteringPointFactory.createPoint(
            cameraPreviewView.width / 2F + command.offsetX,
            cameraPreviewView.height / 2F + command.offsetY,
        )
        val focusActionBuilder = FocusMeteringAction.Builder(focusPoint)
        if (command.resetDelayMs > 0L) {
            focusActionBuilder.setAutoCancelDuration(command.resetDelayMs, TimeUnit.MILLISECONDS)
        } else {
            focusActionBuilder.disableAutoCancel()
        }

        return activeCamera.cameraControl.startFocusAndMetering(focusActionBuilder.build())
            .asCameraControlDeferred(mainExecutor, CameraControlOperation.FOCUS)
    }

    /** Clears focus state left by the previous platform-view owner. */
    private fun executeResetFocus(): Deferred<Unit> {
        val activeCamera = (bindingState as? BoundCamera)?.camera
            ?: throw PluginError.CameraIsNotInitialized
        return activeCamera.cameraControl.cancelFocusAndMetering()
            .asCameraControlDeferred(mainExecutor, CameraControlOperation.FOCUS)
    }

    /** Applies an absolute zoom ratio and completes after the camera accepts the value. */
    private fun executeZoomRatio(value: Float): Deferred<Unit> {
        val current = bindingState as? BoundCamera
            ?: throw PluginError.CameraIsNotInitialized
        val zoomState = current.camera.cameraInfo.zoomState.value
            ?: throw PluginError.CameraIsNotInitialized
        if (!value.isFinite() || value !in zoomState.minZoomRatio..zoomState.maxZoomRatio) {
            throw PluginError.InvalidArguments
        }

        return current.camera.cameraControl.setZoomRatio(value)
            .asCameraControlDeferred(mainExecutor, CameraControlOperation.ZOOM)
    }

    /** Applies an absolute torch state for the active camera. */
    private fun executeTorch(enabled: Boolean): Deferred<Unit> {
        val current = bindingState as? BoundCamera
            ?: throw PluginError.CameraIsNotInitialized
        if (!isFlashSupported(current)) {
            if (!enabled) return CompletableDeferred(Unit)
            throw PluginError.DeviceHasNotFlash
        }

        return current.camera.cameraControl.enableTorch(enabled)
            .asCameraControlDeferred(mainExecutor, CameraControlOperation.TORCH)
    }

    /** Reveals preview only after session-level startup configuration is complete. */
    override fun showPreview() {
        if (!isBound()) throw PluginError.CameraIsNotInitialized
        previewView.alpha = 1.0F
    }

    /** Hides preview without changing camera bindings or analysis resources. */
    override fun hidePreview() {
        previewView.alpha = 0.0F
    }

    /** Removes the active use-case binding while leaving adapter resources reusable. */
    override fun unbind() {
        if (bindingState === BindingState.Disposed || bindingState === BindingState.Idle) return
        val current = bindingState as? BoundCamera
        previewView.alpha = 0.0F
        if (current != null) {
            unbindCamera(current)
            if (bindingState === current) bindingState = BindingState.Idle
        } else {
            bindingState = BindingState.Idle
        }
    }

    /** Releases camera bindings and resources owned by this adapter. */
    override fun dispose() {
        if (bindingState === BindingState.Disposed) return
        unbind()
        bindingState = BindingState.Disposed
        cameraPreviewView.removeOnLayoutChangeListener(previewLayoutChangeListener)
        displayManager.unregisterDisplayListener(displayListener)
        nv21Converter.dispose()
        cameraProvider = null
    }

    /** Completes startup or refreshes use cases when preview geometry changes. */
    private fun bindForCurrentViewPort() {
        val provider = cameraProvider ?: return
        val viewPort = cameraPreviewView.viewPort ?: return
        when (val current = bindingState) {
            is PendingStart -> {
                try {
                    val bound = bindCamera(
                        provider,
                        current.lifecycleOwner,
                        current.analysisExecutor,
                        current.onFrame,
                        current.onAvailabilityChanged,
                        viewPort,
                    )
                    bindingState = bound
                    observeCameraState(bound)
                    try {
                        current.onInit()
                    } catch (error: Exception) {
                        bindingState = BindingState.Idle
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

            BindingState.Idle, BindingState.Disposed -> Unit
        }
    }

    /** Binds preview and analysis with one viewport so both expose the same field of view. */
    private fun bindCamera(
        provider: ProcessCameraProvider,
        lifecycleOwner: LifecycleOwner,
        analysisExecutor: ExecutorService,
        onFrame: OnCameraFrame,
        onAvailabilityChanged: OnCameraAvailabilityChanged,
        viewPort: ViewPort,
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
                onAvailabilityChanged = onAvailabilityChanged,
                camera = camera,
                preview = preview,
                imageAnalysis = imageAnalysis,
                useCaseGroup = useCaseGroup,
                viewPort = viewPort,
                cameraStateObserver = createCameraStateObserver(camera),
            )
        } catch (error: Exception) {
            imageAnalysis.clearAnalyzer()
            throw error
        }
    }

    /** Rebinds for a new viewport and restores the prior group if the change fails. */
    private fun rebindCamera(
        provider: ProcessCameraProvider,
        current: BoundCamera,
        viewPort: ViewPort,
    ) {
        stopObservingCameraState(current)
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
                current.onAvailabilityChanged,
                viewPort,
            )
            bindingState = replacement
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
                    ),
                    availability = CameraAvailability.Closed(),
                )
                bindingState = restored
                observeCameraState(restored)
            } catch (restoreError: Exception) {
                current.imageAnalysis.clearAnalyzer()
                bindingState = BindingState.Idle
                previewView.alpha = 0.0F
                Log.e(TAG, "Unable to restore CameraX use cases after viewport update", restoreError)
            }
            Log.w(TAG, "Unable to update CameraX viewport", error)
        }
    }

    /** Creates the preview pipeline. */
    private fun createPreview(targetRotation: Int): Preview = Preview.Builder()
        .setTargetRotation(targetRotation)
        .setResolutionSelector(DEFAULT_RESOLUTION_SELECTOR)
        .build()
        .also {
            it.surfaceProvider = cameraPreviewView.surfaceProvider
        }

    /** Creates the image-analysis pipeline. */
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
                        previewCropState = previewCropState,
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
        try {
            cameraProvider?.unbind(current.preview, current.imageAnalysis)
        } catch (error: Exception) {
            Log.w(TAG, "Unable to unbind CameraX use cases", error)
        } finally {
            current.imageAnalysis.clearAnalyzer()
        }
    }

    /** Subscribes to device state and immediately processes the latest available value. */
    private fun observeCameraState(current: BoundCamera) {
        current.camera.cameraInfo.cameraState.observeForever(current.cameraStateObserver)
        onCameraStateChanged(
            current.camera,
            current.camera.cameraInfo.cameraState.value,
        )
    }

    /** Removes the device-state observer and reports that this binding is no longer open. */
    private fun stopObservingCameraState(current: BoundCamera) {
        current.camera.cameraInfo.cameraState.removeObserver(current.cameraStateObserver)
        updateAvailability(current, CameraAvailability.Closed())
    }

    /** Returns whether this completed camera binding exposes a flash unit. */
    private fun isFlashSupported(current: BoundCamera): Boolean =
        current.camera.cameraInfo.hasFlashUnit()

    /** Creates an observer tied to one concrete CameraX binding. */
    private fun createCameraStateObserver(camera: AndroidXCamera) =
        Observer<AndroidXCameraState> { state -> onCameraStateChanged(camera, state) }

    /** Forwards only OPEN/CLOSED availability when the event belongs to the active binding. */
    private fun onCameraStateChanged(
        sourceCamera: AndroidXCamera,
        state: AndroidXCameraState?,
    ) {
        val current = bindingState as? BoundCamera ?: return
        if (current.camera !== sourceCamera) return
        val availability = if (state?.type == AndroidXCameraState.Type.OPEN) {
            CameraAvailability.Open
        } else {
            val stateError = state?.error
            CameraAvailability.Closed(
                errorCode = stateError?.code,
                cause = stateError?.cause,
            )
        }
        updateAvailability(current, availability)
    }

    /** Deduplicates CameraX's intermediate states into the adapter's OPEN/CLOSED contract. */
    private fun updateAvailability(
        current: BoundCamera,
        availability: CameraAvailability,
    ) {
        if (bindingState !== current) return
        val isDuplicate = when {
            current.availability is CameraAvailability.Open &&
                availability is CameraAvailability.Open -> true
            current.availability is CameraAvailability.Closed &&
                availability is CameraAvailability.Closed &&
                availability.errorCode == null -> true
            else -> false
        }
        if (isDuplicate) return
        current.availability = availability
        current.onAvailabilityChanged(availability)
    }

    /** Fails a start request only while it is still the current binding state. */
    private fun failPendingStart(request: PendingStart, error: Exception) {
        if (bindingState !== request) return
        bindingState = BindingState.Idle
        request.onError(error)
    }

    private sealed interface BindingState {
        object Idle : BindingState

        object Disposed : BindingState
    }

    private class PendingStart(
        val lifecycleOwner: LifecycleOwner,
        val analysisExecutor: ExecutorService,
        val onFrame: OnCameraFrame,
        val onAvailabilityChanged: OnCameraAvailabilityChanged,
        val onInit: OnInit,
        val onError: OnError,
    ) : BindingState

    private data class BoundCamera(
        val lifecycleOwner: LifecycleOwner,
        val analysisExecutor: ExecutorService,
        val onFrame: OnCameraFrame,
        val onAvailabilityChanged: OnCameraAvailabilityChanged,
        val camera: AndroidXCamera,
        val preview: Preview,
        val imageAnalysis: ImageAnalysis,
        val useCaseGroup: UseCaseGroup,
        val viewPort: ViewPort,
        val cameraStateObserver: Observer<AndroidXCameraState>,
        var availability: CameraAvailability = CameraAvailability.Closed(),
    ) : BindingState

    /** Preview size changes are handled in place; transform changes require a rebind. */
    private fun ViewPort.requiresRebind(other: ViewPort): Boolean =
        rotation != other.rotation ||
            scaleType != other.scaleType ||
            layoutDirection != other.layoutDirection

    private data class PreviewSize(
        val width: Int,
        val height: Int,
    )

    private companion object {
        const val TAG = "MlkitScannerCamera"

        /** Default target resolution used by preview and analysis. */
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
