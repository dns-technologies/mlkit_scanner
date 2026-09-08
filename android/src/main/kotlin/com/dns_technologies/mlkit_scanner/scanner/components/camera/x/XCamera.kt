package com.dns_technologies.mlkit_scanner.scanner.components.camera.x

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Size
import android.view.View
import android.view.ViewGroup
import androidx.annotation.MainThread
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
import com.dns_technologies.mlkit_scanner.PluginConstants
import com.dns_technologies.mlkit_scanner.PluginError
import com.dns_technologies.mlkit_scanner.scanner.components.camera.Camera
import com.dns_technologies.mlkit_scanner.scanner.components.camera.CameraAvailability
import com.dns_technologies.mlkit_scanner.scanner.components.camera.OnCameraFrame
import com.dns_technologies.mlkit_scanner.scanner.components.camera.OnCameraAvailabilityChanged
import com.dns_technologies.mlkit_scanner.scanner.components.camera.OnError
import com.dns_technologies.mlkit_scanner.scanner.components.camera.OnInit
import com.dns_technologies.mlkit_scanner.scanner.utils.ImageProxyNv21Converter
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred

/**
 * Owns one CameraX preview/analysis binding. Desired controls belong to the scanner session.
 *
 * Lifecycle and control entry points run on the main thread. Analyzer callbacks run on the
 * caller's executor and borrow each frame only until their synchronous callback returns.
 */
@MainThread
class XCamera internal constructor(
    context: Context,
    private val cameraPreviewView: PreviewView,
    private val providerLoader: () -> ListenableFuture<ProcessCameraProvider>,
) : Camera {
    /** Creates the platform preview and uses the application-wide CameraX provider. */
    constructor(context: Context) : this(
        context,
        createPreviewView(context),
        { ProcessCameraProvider.getInstance(context.applicationContext) },
    )

    private val nv21Converter = ImageProxyNv21Converter()
    private val mainExecutor = ContextCompat.getMainExecutor(context)
    private val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private var cameraProvider: ProcessCameraProvider? = null

    // Published to the analysis executor so queued callbacks can reject obsolete bindings.
    @Volatile
    private var bindingState: BindingState = Idle

    @Volatile
    private var previewSize = Size(0, 0)

    override val previewView: View
        get() = cameraPreviewView

    private val layoutListener = View.OnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
        previewSize = Size(right - left, bottom - top)
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
        cameraPreviewView.addOnLayoutChangeListener(layoutListener)
        displayManager.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
    }

    override fun bind(
        lifecycleOwner: LifecycleOwner,
        analysisExecutor: ExecutorService,
        onFrame: OnCameraFrame,
        onAvailabilityChanged: OnCameraAvailabilityChanged,
        onInit: OnInit,
        onError: OnError,
    ) {
        when (bindingState) {
            Disposed -> throw PluginError.CameraSessionDisposed
            Idle -> Unit
            else -> return
        }
        val request = PendingStart(
            lifecycleOwner, analysisExecutor, onFrame, onAvailabilityChanged, onInit, onError,
        )
        bindingState = request
        try {
            val future = providerLoader()
            future.addListener({
                if (bindingState !== request) return@addListener
                try {
                    // The listener runs only after completion; this get never waits on the UI thread.
                    cameraProvider = future.get()
                    bindForCurrentViewPort()
                } catch (error: Exception) {
                    failBinding(request, request.onError, error)
                }
            }, mainExecutor)
        } catch (error: Exception) {
            failBinding(request, request.onError, error)
        }
    }

    override fun isBound(): Boolean = bindingState is BoundCamera

    override fun resetFocus(): Deferred<Unit> =
        requireCamera().cameraControl.cancelFocusAndMetering()
            .asCameraControlDeferred(CameraControlOperation.FOCUS)

    override fun focus(resetDelayMs: Long, x: Float, y: Float): Deferred<Unit> {
        val camera = requireCamera()
        if (!x.isFinite() || !y.isFinite()) throw PluginError.InvalidArguments
        if (cameraPreviewView.width == 0 || cameraPreviewView.height == 0) return CompletableDeferred(Unit)
        val point = cameraPreviewView.meteringPointFactory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(point).apply {
            if (resetDelayMs > 0) {
                setAutoCancelDuration(resetDelayMs, TimeUnit.MILLISECONDS)
            } else {
                disableAutoCancel()
            }
        }.build()
        return camera.cameraControl.startFocusAndMetering(action)
            .asCameraControlDeferred(CameraControlOperation.FOCUS)
    }

    override fun setZoomRatio(ratio: Float): Deferred<Unit> {
        val camera = requireCamera()
        val zoom = camera.cameraInfo.zoomState.value ?: throw PluginError.CameraIsNotInitialized
        if (!ratio.isFinite() || ratio !in zoom.minZoomRatio..zoom.maxZoomRatio) {
            throw PluginError.InvalidArguments
        }
        return camera.cameraControl.setZoomRatio(ratio)
            .asCameraControlDeferred(CameraControlOperation.ZOOM)
    }

    override fun setTorch(enabled: Boolean): Deferred<Unit> {
        val camera = requireCamera()
        if (!camera.cameraInfo.hasFlashUnit()) {
            if (enabled) throw PluginError.DeviceHasNotFlash
            return CompletableDeferred(Unit)
        }
        return camera.cameraControl.enableTorch(enabled)
            .asCameraControlDeferred(CameraControlOperation.TORCH)
    }

    override fun showPreview() {
        if (!isBound()) throw PluginError.CameraIsNotInitialized
        cameraPreviewView.alpha = 1F
    }

    override fun hidePreview() {
        // COMPATIBLE uses a TextureView. Keeping its alpha preserves the last rendered frame.
    }

    override fun unbind() {
        val previous = bindingState
        if (previous === Disposed) return
        // Invalidate first: CLOSED callbacks may synchronously bind or dispose the adapter.
        bindingState = Idle
        if (previous is BoundCamera) release(previous)
    }

    override fun dispose() {
        val previous = bindingState
        if (previous === Disposed) return
        bindingState = Disposed
        try {
            if (previous is BoundCamera) release(previous)
        } finally {
            cameraPreviewView.removeOnLayoutChangeListener(layoutListener)
            displayManager.unregisterDisplayListener(displayListener)
            nv21Converter.dispose()
            cameraProvider = null
        }
    }

    private fun requireCamera(): AndroidXCamera =
        (bindingState as? BoundCamera)?.camera ?: throw PluginError.CameraIsNotInitialized

    private fun bindForCurrentViewPort() {
        val provider = cameraProvider ?: return
        val viewPort = cameraPreviewView.viewPort ?: return
        when (val current = bindingState) {
            is PendingStart -> {
                if (current.binding) return
                current.binding = true
                val bound = try {
                    bindCamera(provider, current, viewPort)
                } catch (error: Exception) {
                    failBinding(current, current.onError, error)
                    return
                }
                if (bindingState === current) activate(bound)
                else release(bound, provider)
            }
            is BoundCamera -> if (current.viewPort.needsUpdate(viewPort)) {
                updateViewPort(provider, current, viewPort)
            }
            Idle, Disposed -> Unit
        }
    }

    /** Updates geometry on the same use cases, preserving camera controls and the analyzer. */
    private fun updateViewPort(provider: ProcessCameraProvider, current: BoundCamera, viewPort: ViewPort) {
        // Publish before SDK calls so a synchronous layout callback cannot repeat this update.
        current.viewPort = viewPort
        try {
            current.preview.targetRotation = viewPort.rotation
            current.imageAnalysis.targetRotation = viewPort.rotation
            val group = UseCaseGroup.Builder()
                .setViewPort(viewPort)
                .addUseCase(current.preview)
                .addUseCase(current.imageAnalysis)
                .build()
            if (bindingState !== current) return
            provider.bindToLifecycle(current.request.lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, group)
            if (bindingState !== current) provider.unbind(current.preview, current.imageAnalysis)
        } catch (error: Exception) {
            if (bindingState !== current) return
            bindingState = Idle
            try {
                release(current, provider)
            } finally {
                current.request.onError(error)
            }
        }
    }

    /** Publishes ownership before observing LiveData, which can call back synchronously. */
    private fun activate(bound: BoundCamera) {
        bindingState = bound
        try {
            observe(bound)
            if (bindingState === bound) bound.request.onInit()
        } catch (error: Exception) {
            if (bindingState !== bound) return // A callback already detached/disposed or replaced us.
            bindingState = Idle
            try {
                release(bound)
            } finally {
                bound.request.onError(error)
            }
        }
    }

    private fun bindCamera(
        provider: ProcessCameraProvider,
        request: PendingStart,
        viewPort: ViewPort,
    ): BoundCamera {
        val preview = Preview.Builder()
            .setTargetRotation(viewPort.rotation)
            .setResolutionSelector(RESOLUTION_SELECTOR)
            .build()
            .also { it.surfaceProvider = cameraPreviewView.surfaceProvider }
        val analysis = ImageAnalysis.Builder()
            .setTargetRotation(viewPort.rotation)
            .setResolutionSelector(RESOLUTION_SELECTOR)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        analysis.setAnalyzer(request.analysisExecutor) { image ->
            var frame: XCameraFrame? = null
            try {
                if ((bindingState as? BoundCamera)?.imageAnalysis !== analysis) return@setAnalyzer
                val size = previewSize
                frame = XCameraFrame(image, nv21Converter, size.width, size.height)
                request.onFrame(frame)
            } catch (error: Exception) {
                Log.e(PluginConstants.LOG_TAG, "Camera frame processing failed", error)
            } finally {
                frame?.close() ?: image.close()
            }
        }
        val group = UseCaseGroup.Builder()
            .setViewPort(viewPort)
            .addUseCase(preview)
            .addUseCase(analysis)
            .build()
        return try {
            val camera = provider.bindToLifecycle(request.lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, group)
            BoundCamera(request, camera, preview, analysis, viewPort)
        } catch (error: Exception) {
            analysis.clearAnalyzer()
            try {
                provider.unbind(preview, analysis)
            } catch (cleanupError: Exception) {
                if (cleanupError !== error) error.addSuppressed(cleanupError)
            }
            throw error
        }
    }

    /** Releases only this adapter's use cases; the provider and executor belong to other owners. */
    private fun release(current: BoundCamera, provider: ProcessCameraProvider? = cameraProvider) {
        stopObserving(current)
        try {
            provider?.unbind(current.preview, current.imageAnalysis)
        } catch (error: Exception) {
            Log.w(PluginConstants.LOG_TAG, "Unable to unbind CameraX use cases", error)
        } finally {
            current.imageAnalysis.clearAnalyzer()
        }
        notifyClosed(current)
    }

    private fun observe(current: BoundCamera) {
        current.camera.cameraInfo.cameraState.observeForever(current.cameraStateObserver)
        if (bindingState === current) {
            cameraPreviewView.previewStreamState.observeForever(current.previewStreamObserver)
        }
    }

    private fun stopObserving(current: BoundCamera) {
        current.camera.cameraInfo.cameraState.removeObserver(current.cameraStateObserver)
        cameraPreviewView.previewStreamState.removeObserver(current.previewStreamObserver)
    }

    private fun notifyClosed(current: BoundCamera) {
        if (current.availability == CameraAvailability.Open) {
            current.availability = CameraAvailability.Closed()
            current.request.onAvailabilityChanged(current.availability)
        }
    }

    private fun publishAvailability(current: BoundCamera) {
        if (bindingState !== current) return
        val state = current.camera.cameraInfo.cameraState.value
        val next = if (state?.type == AndroidXCameraState.Type.OPEN && state.error == null &&
            cameraPreviewView.previewStreamState.value == PreviewView.StreamState.STREAMING
        ) {
            CameraAvailability.Open
        } else {
            CameraAvailability.Closed(state?.error?.code, state?.error?.cause)
        }
        // Preserve error events, but suppress repeated OPEN and ordinary CLOSED notifications.
        if (next == CameraAvailability.Open && current.availability == next) return
        if (next is CameraAvailability.Closed && next.errorCode == null &&
            current.availability is CameraAvailability.Closed
        ) return
        current.availability = next
        current.request.onAvailabilityChanged(next)
    }

    private fun failBinding(expected: BindingState, onError: OnError, error: Exception) {
        if (bindingState !== expected) return
        bindingState = Idle
        onError(error)
    }

    private sealed interface BindingState

    private data object Idle : BindingState
    private data object Disposed : BindingState

    private class PendingStart(
        val lifecycleOwner: LifecycleOwner,
        val analysisExecutor: ExecutorService,
        val onFrame: OnCameraFrame,
        val onAvailabilityChanged: OnCameraAvailabilityChanged,
        val onInit: OnInit,
        val onError: OnError,
    ) : BindingState {
        var binding = false
    }

    private inner class BoundCamera(
        val request: PendingStart,
        val camera: AndroidXCamera,
        val preview: Preview,
        val imageAnalysis: ImageAnalysis,
        var viewPort: ViewPort,
    ) : BindingState {
        var availability: CameraAvailability = CameraAvailability.Closed()
        // Identity is this binding, not CameraX's Camera, which can survive a rebind.
        val cameraStateObserver = Observer<AndroidXCameraState> { publishAvailability(this) }
        val previewStreamObserver = Observer<PreviewView.StreamState> { publishAvailability(this) }
    }

    /** Both use cases must share the current visible sensor area after layout or rotation. */
    private fun ViewPort.needsUpdate(other: ViewPort): Boolean =
        rotation != other.rotation || aspectRatio != other.aspectRatio ||
            scaleType != other.scaleType || layoutDirection != other.layoutDirection

    private companion object {
        /**
         * Preserves the operation and original failure. Cancelling this deferred requests
         * non-interrupting cancellation, which CameraX may ignore; it does not undo hardware work.
         * Cancelling an awaiting coroutine is independent of cancelling the deferred itself.
         */
        fun ListenableFuture<*>.asCameraControlDeferred(operation: CameraControlOperation): Deferred<Unit> {
            val result = CompletableDeferred<Unit>()
            fun fail(error: Exception) {
                val cause = if (error is ExecutionException) error.cause ?: error else error
                result.completeExceptionally(PluginError.CameraControlError(
                    operation, cause = cause,
                ))
            }
            result.invokeOnCompletion { cause ->
                if (cause is CancellationException) cancel(false)
            }
            try {
                addListener({
                    if (result.isCompleted) return@addListener
                    try {
                        get()
                        result.complete(Unit)
                    } catch (error: Exception) {
                        fail(error)
                    }
                }, Runnable::run) // The completed future's get() cannot block this listener.
            } catch (error: Exception) {
                fail(error)
            }
            return result
        }

        fun createPreviewView(context: Context) = PreviewView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            )
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }

        val RESOLUTION_SELECTOR = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(Size(720, 1280), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
            )
            .build()
    }
}
