package com.dns_technologies.mlkit_scanner.scanner.components.camera.x

import android.content.Context
import android.graphics.PointF
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Rational
import android.util.Size
import android.view.Display
import android.view.Surface
import androidx.annotation.MainThread
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera as AndroidXCamera
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState as AndroidXCameraState
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import com.dns_technologies.mlkit_scanner.PluginConstants
import com.dns_technologies.mlkit_scanner.PluginError
import com.dns_technologies.mlkit_scanner.scanner.components.camera.*
import com.dns_technologies.mlkit_scanner.scanner.utils.ImageProxyNv21Converter
import com.google.common.util.concurrent.ListenableFuture
import io.flutter.view.TextureRegistry
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred

/** Owns CameraX binding readiness, device controls and persistent preview output. */
@MainThread
@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
internal class XCamera(
    context: Context,
    producer: TextureRegistry.SurfaceProducer,
    publish: (CameraPreviewDescription?) -> Unit = {},
    /** Loads the process camera provider; injectable for deterministic binding tests. */
    private val providerLoader: () -> ListenableFuture<ProcessCameraProvider> = {
        ProcessCameraProvider.getInstance(context.applicationContext)
    },
) : Camera {
    override var onPreviewChanged: (CameraPreviewDescription?) -> Unit = publish
    /** Reusable NV21 conversion buffers shared by analysis frames. */
    private val nv21Converter = ImageProxyNv21Converter()
    /** Serial executor for CameraX state and preview callbacks. */
    private val mainExecutor = ContextCompat.getMainExecutor(context)
    /** System display source used to follow device rotation. */
    private val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    /** Persistent Flutter texture output owned by this camera adapter. */
    private val output = CameraTextureOutput(producer, mainExecutor) { onPreviewChanged(it) }
    override val disposal: Deferred<Unit>
        get() = output.disposal

    /** Loaded process provider used to unbind only this adapter's use cases. */
    private var provider: ProcessCameraProvider? = null
    /** Current SDK binding identity, visible to the analysis worker. */
    @Volatile private var binding: Binding? = null
    /** Latest preview viewport size, published to the analysis worker. */
    @Volatile private var previewSize = Size(1, 1)
    /** Startup request still awaiting its provider and use-case binding. */
    private var pending: Start? = null
    /** Terminal flag rejecting new bindings and late callbacks. */
    private var disposed = false
    /** Completion shared by callers waiting for the current binding to open. */
    private var readiness = CompletableDeferred<Unit>()
    /** Current attempt identity, retained through cleanup to reject obsolete failures. */
    private var currentStart: Start? = null
    override val torchEnabled: Boolean?
        get() = binding?.camera?.cameraInfo?.torchState?.value?.let { it == androidx.camera.core.TorchState.ON }
    override val isReady: Boolean
        get() = !disposed && binding?.initialized == true && binding?.availability == CameraAvailability.Open
    /** Startup context retained only while Flutter's surface is unavailable. */
    private var suspended: Start? = null
    /** Current default-display rotation. */
    private val rotation: Int
        get() = displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: Surface.ROTATION_0

    /** Updates both use cases when the default display rotates. */
    private val displayListener =
        object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(id: Int) = Unit

            override fun onDisplayRemoved(id: Int) = Unit

            override fun onDisplayChanged(id: Int) {
                if (id != Display.DEFAULT_DISPLAY) return
                binding?.let {
                    it.preview.targetRotation = rotation
                    it.analysis.targetRotation = rotation
                }
            }
        }

    init {
        displayManager.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
        output.onSurfaceLost = {
            currentStart = null
            val start = binding?.start ?: pending
            suspended = start
            detachBinding()
        }
        output.onSurfaceRestored = {
            val start = suspended
            suspended = null
            if (start != null && !disposed)
                bind(
                    start.owner,
                    start.executor,
                    start.onFrame,
                    start.onAvailability,
                    start.onError,
                )
        }
    }

    override fun updateGeometry(size: Size) {
        previewSize = size
    }

    override fun isBound() = binding != null

    override fun bind(
        lifecycleOwner: LifecycleOwner,
        analysisExecutor: ExecutorService,
        onFrame: OnCameraFrame,
        onAvailabilityChanged: OnCameraAvailabilityChanged,
        onError: OnError,
    ): Deferred<Unit> {
        if (disposed) throw PluginError.CameraSessionDisposed
        if (binding != null || pending != null || suspended != null) return readiness
        if (readiness.isCompleted) readiness = CompletableDeferred()
        val completion = readiness
        val start =
            Start(lifecycleOwner, analysisExecutor, onFrame, onAvailabilityChanged, onError)
        startBinding(start)
        return completion
    }

    /** Installs one SDK binding while keeping the completion independent of its waiters. */
    private fun startBinding(start: Start) {
        currentStart = start
        val completion = readiness
        pending = start
        val future = providerLoader()
        future.addListener(
            {
                if (pending !== start || disposed) return@addListener
                var attemptedPreview: Preview? = null
                var attemptedAnalysis: ImageAnalysis? = null
                try {
                    val loaded = future.get()
                    provider = loaded
                    val preview = createPreview(start)
                    attemptedPreview = preview
                    val analysis =
                        ImageAnalysis.Builder()
                            .setTargetRotation(rotation)
                            .setResolutionSelector(RESOULTION)
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                    attemptedAnalysis = analysis
                    analysis.setAnalyzer(start.executor) { image ->
                        var frame: XCameraFrame? = null
                        try {
                            if (binding?.analysis !== analysis) return@setAnalyzer
                            val size = previewSize
                            frame = XCameraFrame(image, nv21Converter, size.width, size.height)
                            start.onFrame(frame)
                        } catch (error: Exception) {
                            Log.e(PluginConstants.LOG_TAG, "Camera analysis failed", error)
                        } finally {
                            frame?.close() ?: image.close()
                        }
                    }
                    val group =
                        UseCaseGroup.Builder()
                            .setViewPort(
                                // Keep the full sensor area in both outputs to avoid discarding source pixels.
                                ViewPort.Builder(Rational(16, 9), Surface.ROTATION_0)
                                    .setScaleType(ViewPort.FIT)
                                    .build()
                            )
                            .addUseCase(preview)
                            .addUseCase(analysis)
                            .build()
                    val camera =
                        loaded.bindToLifecycle(
                            start.owner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            group,
                        )
                    if (pending !== start || disposed) {
                        loaded.unbind(preview, analysis)
                        analysis.clearAnalyzer()
                        return@addListener
                    }
                    val current = Binding(start, camera, preview, analysis)
                    pending = null
                    binding = current
                    camera.cameraInfo.cameraState.observeForever(current.observer)
                    if (binding === current && !disposed) {
                        current.initialized = true
                        current.publishAvailability()
                    }
                } catch (error: Exception) {
                    if (pending === start) pending = null
                    if (binding?.start === start) detachBinding()
                    else {
                        val cases = listOfNotNull(attemptedPreview, attemptedAnalysis)
                        if (cases.isNotEmpty()) provider?.unbind(*cases.toTypedArray())
                        attemptedAnalysis?.clearAnalyzer()
                    }
                    if (currentStart === start && !disposed) {
                        try {
                            completion.completeExceptionally(error)
                            if (currentStart === start && !disposed) start.onError(error)
                        } finally {
                            if (currentStart === start) currentStart = null
                        }
                    }
                }
            },
            mainExecutor,
        )
    }

    /** Creates a camera preview with frame-readiness notifications. */
    private fun createPreview(start: Start): Preview {
        val builder =
            Preview.Builder().setTargetRotation(rotation).setResolutionSelector(RESOULTION)
        Camera2Interop.Extender(builder)
            .setSessionCaptureCallback(
                object : CameraCaptureSession.CaptureCallback() {
                    /**
                     * Recent real captures awaiting completion, correlated to their surface
                     * requests.
                     */
                    private val frames = mutableMapOf<Long, CameraTextureOutput.PreviewFrame>()

                    override fun onCaptureStarted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        timestamp: Long,
                        frameNumber: Long,
                    ) {
                        mainExecutor.execute {
                            if (binding?.start === start) {
                                output.frameStarted(frameNumber)?.let { frames[frameNumber] = it }
                                // Keep only actual recent camera frames when a device
                                // omits a completion callback.
                                frames.keys.removeAll { it < frameNumber - 8 }
                            }
                        }
                    }

                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult,
                    ) {
                        mainExecutor.execute {
                            val frame = frames.remove(result.frameNumber)
                            if (binding?.start === start && frame != null)
                                output.frameCaptured(frame)
                        }
                    }
                }
            )
        return builder.build().also { it.setSurfaceProvider(output) }
    }

    override fun resetFocus(): Deferred<Unit> {
        val camera = requireCamera()
        return cameraCall("Failed to reset camera focus") {
            camera.cameraControl.cancelFocusAndMetering()
        }
    }

    override fun focus(resetDelayMs: Long, x: Float, y: Float): Deferred<Unit> {
        val current = binding ?: throw PluginError.CameraIsNotInitialized
        if (!x.isFinite() || !y.isFinite()) throw PluginError.InvalidArguments
        val resolution = current.preview.resolutionInfo ?: throw PluginError.CameraIsNotInitialized
        val crop = resolution.cropRect
        val degree = current.camera.cameraInfo.getSensorRotationDegrees(rotation)
        val point =
            sourcePoint(
                x / previewSize.width,
                y / previewSize.height,
                crop.width().toFloat(),
                crop.height().toFloat(),
                previewSize,
                degree,
            )
        val factory =
            SurfaceOrientedMeteringPointFactory(
                resolution.resolution.width.toFloat(),
                resolution.resolution.height.toFloat(),
                current.preview,
            )
        val metering = factory.createPoint(crop.left + point.x, crop.top + point.y)
        val action =
            FocusMeteringAction.Builder(metering)
                .apply {
                    if (resetDelayMs > 0) setAutoCancelDuration(resetDelayMs, TimeUnit.MILLISECONDS)
                    else disableAutoCancel()
                }
                .build()
        return cameraCall("Failed to focus camera at ($x, $y)") {
            current.camera.cameraControl.startFocusAndMetering(action)
        }
    }

    override fun setZoomRatio(ratio: Float): Deferred<Unit> {
        val camera = requireCamera()
        val zoom = camera.cameraInfo.zoomState.value ?: throw PluginError.CameraIsNotInitialized
        if (!ratio.isFinite() || ratio !in zoom.minZoomRatio..zoom.maxZoomRatio)
            throw PluginError.InvalidArguments
        return cameraCall("Failed to set camera zoom ratio to $ratio") {
            camera.cameraControl.setZoomRatio(ratio)
        }
    }

    override fun setTorch(enabled: Boolean): Deferred<Unit> {
        val camera = requireCamera()
        if (!camera.cameraInfo.hasFlashUnit()) {
            if (enabled) throw PluginError.DeviceHasNotFlash
            return CompletableDeferred(Unit)
        }
        return cameraCall("Failed to set camera torch to $enabled") {
            camera.cameraControl.enableTorch(enabled)
        }
    }

    /** Returns the active SDK camera or reports an uninitialized scanner. */
    private fun requireCamera() = binding?.camera ?: throw PluginError.CameraIsNotInitialized

    override fun unbind() {
        currentStart = null
        suspended = null
        readiness.cancel()
        readiness = CompletableDeferred()
        detachBinding()
    }

    /** Detaches use cases, preserving pending starts across temporary surface loss. */
    private fun detachBinding() {
        pending = null
        val previous = binding
        binding = null
        if (readiness.isCompleted) readiness = CompletableDeferred()
        if (previous != null) {
            previous.camera.cameraInfo.cameraState.removeObserver(previous.observer)
            provider?.unbind(previous.preview, previous.analysis)
            previous.analysis.clearAnalyzer()
            output.pause()
            previous.start.onAvailability(CameraAvailability.Closed())
        }
    }

    override fun dispose() {
        if (disposed) return
        disposed = true
        suspended = null
        try {
            unbind()
        } finally {
            output.dispose()
            displayManager.unregisterDisplayListener(displayListener)
            nv21Converter.dispose()
        }
    }

    private class Start(
        /** Activity lifecycle borrowed for this binding attempt. */
        val owner: LifecycleOwner,
        /** Worker that owns frame analysis callbacks. */
        val executor: ExecutorService,
        /** Consumer of each camera frame while its borrowed buffer remains valid. */
        val onFrame: OnCameraFrame,
        /** Receives camera readiness transitions for this binding. */
        val onAvailability: OnCameraAvailabilityChanged,
        /** Reports terminal startup failures to the scanner. */
        val onError: OnError,
    )

    private inner class Binding(
        /** Startup context whose identity owns this bound camera. */
        val start: Start,
        /** CameraX camera used for readiness observation and device controls. */
        val camera: AndroidXCamera,
        /** Preview use case connected to the persistent Flutter texture. */
        val preview: Preview,
        /** Frame-analysis use case owned by this binding. */
        val analysis: ImageAnalysis,
    ) {
        /** Last delivered state used to suppress duplicate readiness notifications. */
        var availability: CameraAvailability? = null
            private set
        /** Whether binding setup returned without disposal or callback failure. */
        var initialized = false
        /** Converts CameraX state changes into deduplicated availability events. */
        val observer =
            Observer<AndroidXCameraState> { state ->
                if (binding === this) updateAvailability(state)
            }

        /** Converts SDK state without publishing duplicate device transitions. */
        private fun updateAvailability(state: AndroidXCameraState) {
            val next =
                if (state.type == AndroidXCameraState.Type.OPEN && state.error == null)
                    CameraAvailability.Open
                else CameraAvailability.Closed(state.error?.code, state.error?.cause)
            if (next == availability) return
            availability = next
            if (next == CameraAvailability.Open) {
                if (readiness.isCancelled) readiness = CompletableDeferred()
            } else {
                if (readiness.isCompleted) readiness = CompletableDeferred()
                val closed = next as CameraAvailability.Closed
                if (closed.errorCode != null) readiness.completeExceptionally(
                    PluginError.CameraError(
                        "Camera failed to open", cause = closed.cause,
                        cameraStateErrorCode = closed.errorCode,
                    )
                )
            }
            output.cameraAvailable(next == CameraAvailability.Open)
            if (initialized) publishAvailability()
        }

        /** Publishes only after setup, so observers see the readiness being reported. */
        fun publishAvailability() {
            val state = availability ?: return
            start.onAvailability(state)
            if (binding === this && isReady) readiness.complete(Unit)
        }
    }

    companion object {
        /** Resolution policy shared by preview and analysis. */
        private val RESOULTION =
            ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(1280, 720),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                    )
                )
                .build()

        /** Maps a normalized fill-center viewport point into unrotated source coordinates. */
        internal fun sourcePoint(
            x: Float,
            y: Float,
            width: Float,
            height: Float,
            viewport: Size,
            rotation: Int,
        ): PointF {
            val swapped = rotation % 180 != 0
            val uprightWidth = if (swapped) height else width
            val uprightHeight = if (swapped) width else height
            val scale = maxOf(viewport.width / uprightWidth, viewport.height / uprightHeight)
            val visibleWidth = viewport.width / scale
            val visibleHeight = viewport.height / scale
            val uprightX = (uprightWidth - visibleWidth) / 2 + x.coerceIn(0F, 1F) * visibleWidth
            val uprightY = (uprightHeight - visibleHeight) / 2 + y.coerceIn(0F, 1F) * visibleHeight
            return when (rotation) {
                90 -> PointF(uprightY, height - uprightX)
                180 -> PointF(width - uprightX, height - uprightY)
                270 -> PointF(width - uprightY, uprightX)
                else -> PointF(uprightX, uprightY)
            }
        }

        /** Bridges SDK completion and cancellation while preserving native failure causes. */
        fun cameraCall(message: String, call: () -> ListenableFuture<*>): Deferred<Unit> {
            val result = CompletableDeferred<Unit>()
            fun fail(error: Exception) {
                val cause = if (error is ExecutionException) error.cause ?: error else error
                result.completeExceptionally(
                    PluginError.CameraError(message, cause = cause)
                )
            }
            val future = try {
                call()
            } catch (error: Exception) {
                fail(error)
                return result
            }
            result.invokeOnCompletion { cause -> if (cause is CancellationException) future.cancel(false) }
            try {
                future.addListener(
                    {
                        if (result.isCompleted) return@addListener
                        try {
                            future.get()
                            result.complete(Unit)
                        } catch (error: Exception) {
                            fail(error)
                        }
                    },
                    Runnable::run,
                ) // The completed future's get() cannot block this listener.
            } catch (error: Exception) {
                fail(error)
            }
            return result
        }
    }
}
