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
import com.dns_technologies.mlkit_scanner.CameraControlOperation
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

/** CameraX binding with a persistent texture output. No Android View participates in capture. */
@MainThread
@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
internal class XCamera(
    context: Context,
    producer: TextureRegistry.SurfaceProducer,
    publish: (Map<String, Any>?) -> Unit = {},
    /** Loads the process camera provider; injectable for deterministic binding tests. */
    private val providerLoader: () -> ListenableFuture<ProcessCameraProvider> = {
        ProcessCameraProvider.getInstance(context.applicationContext)
    },
) : Camera {
    /** Preview observer owned by the scanner using this camera. */
    override var onPreviewChanged: (Map<String, Any>?) -> Unit = publish
    /** Reusable NV21 conversion buffers shared by analysis frames. */
    private val nv21Converter = ImageProxyNv21Converter()
    /** Serial executor for CameraX state and preview callbacks. */
    private val mainExecutor = ContextCompat.getMainExecutor(context)
    /** System display source used to follow device rotation. */
    private val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    /** Persistent Flutter texture output owned by this camera adapter. */
    private val output = CameraTextureOutput(producer, mainExecutor) { onPreviewChanged(it) }
    /** Completes when CameraX returns all surfaces and the texture producer is released. */
    override val disposal: Deferred<Unit>
        get() = output.disposal

    /** Loaded process provider used to unbind only this adapter's use cases. */
    private var provider: ProcessCameraProvider? = null
    /** Current SDK binding identity, visible to the analysis worker. */
    @Volatile private var binding: Binding? = null
    /** Latest widget viewport size, published to the analysis worker. */
    @Volatile private var previewSize = Size(1, 1)
    /** Startup request still awaiting its provider and use-case binding. */
    private var pending: Start? = null
    /** Terminal flag rejecting new bindings and late callbacks. */
    private var disposed = false
    /** Startup context retained only while Flutter's surface is unavailable. */
    private var suspended: Start? = null
    /** Current default-display rotation, falling back to an upright display. */
    private val rotation: Int
        get() = displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: Surface.ROTATION_0

    /** Updates both use cases when the default display rotates. */
    private val displayListener =
        object : DisplayManager.DisplayListener {
            /** Ignores secondary display creation because capture uses the default display. */
            override fun onDisplayAdded(id: Int) = Unit

            /** Ignores display removal; rotation is resolved from the current default display. */
            override fun onDisplayRemoved(id: Int) = Unit

            /** Applies default-display rotation to the currently bound use cases. */
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
            val start = binding?.start ?: pending
            unbind()
            suspended = start
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
                    start.onInit,
                    start.onError,
                )
        }
    }

    /** Publishes the selected widget's viewport for analysis and focus mapping. */
    override fun updateGeometry(size: Size) {
        previewSize = size
    }

    /** Reports whether CameraX use cases currently have a live binding. */
    override fun isBound() = binding != null

    /** Starts one binding attempt and routes callbacks through its request identity. */
    override fun bind(
        lifecycleOwner: LifecycleOwner,
        analysisExecutor: ExecutorService,
        onFrame: OnCameraFrame,
        onAvailabilityChanged: OnCameraAvailabilityChanged,
        onInit: OnInit,
        onError: OnError,
    ) {
        if (disposed) throw PluginError.CameraSessionDisposed
        if (binding != null || pending != null) return
        val start =
            Start(lifecycleOwner, analysisExecutor, onFrame, onAvailabilityChanged, onInit, onError)
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
                                // Keep the shared sensor area; Flutter applies the widget's cover crop.
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
                    if (binding === current && !disposed) start.onInit()
                } catch (error: Exception) {
                    pending = null
                    if (binding?.start === start) unbind()
                    else {
                        val cases = listOfNotNull(attemptedPreview, attemptedAnalysis)
                        if (cases.isNotEmpty()) provider?.unbind(*cases.toTypedArray())
                        attemptedAnalysis?.clearAnalyzer()
                    }
                    if (!disposed) start.onError(error)
                }
            },
            mainExecutor,
        )
    }

    /** Builds the preview and correlates real capture completions with their surface requests. */
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

                    /** Snapshots the surface request for a camera capture on the main executor. */
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

                    /** Confirms preview readiness only for a frame from the current binding. */
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

    /** Restores continuous focusing through CameraX's cancellable control future. */
    override fun resetFocus() =
        requireCamera()
            .cameraControl
            .cancelFocusAndMetering()
            .asCameraControlDeferred(CameraControlOperation.FOCUS)

    /** Maps viewport coordinates to the source crop and starts focus metering. */
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
        return current.camera.cameraControl
            .startFocusAndMetering(action)
            .asCameraControlDeferred(CameraControlOperation.FOCUS)
    }

    /** Validates the device's supported range before applying absolute zoom. */
    override fun setZoomRatio(ratio: Float): Deferred<Unit> {
        val camera = requireCamera()
        val zoom = camera.cameraInfo.zoomState.value ?: throw PluginError.CameraIsNotInitialized
        if (!ratio.isFinite() || ratio !in zoom.minZoomRatio..zoom.maxZoomRatio)
            throw PluginError.InvalidArguments
        return camera.cameraControl
            .setZoomRatio(ratio)
            .asCameraControlDeferred(CameraControlOperation.ZOOM)
    }

    /** Applies torch state; disabling an unavailable flash remains harmless. */
    override fun setTorch(enabled: Boolean): Deferred<Unit> {
        val camera = requireCamera()
        if (!camera.cameraInfo.hasFlashUnit()) {
            if (enabled) throw PluginError.DeviceHasNotFlash
            return CompletableDeferred(Unit)
        }
        return camera.cameraControl
            .enableTorch(enabled)
            .asCameraControlDeferred(CameraControlOperation.TORCH)
    }

    /** Returns the active SDK camera or reports an uninitialized scanner. */
    private fun requireCamera() = binding?.camera ?: throw PluginError.CameraIsNotInitialized

    /** Revokes binding identity and removes this adapter's use cases and observer. */
    override fun unbind() {
        suspended = null
        pending = null
        val previous = binding
        binding = null
        if (previous != null) {
            previous.camera.cameraInfo.cameraState.removeObserver(previous.observer)
            provider?.unbind(previous.preview, previous.analysis)
            previous.analysis.clearAnalyzer()
            output.pause()
            previous.start.onAvailability(CameraAvailability.Closed())
        }
    }

    /** Closes the binding, texture output, display listener, and conversion buffers. */
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
        /** Acknowledges successful use-case binding. */
        val onInit: OnInit,
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
        private var lastAvailability: CameraAvailability? = null
        /** Converts CameraX state changes into deduplicated availability events. */
        val observer =
            Observer<AndroidXCameraState> { state ->
                if (binding === this) {
                    val next =
                        if (state.type == AndroidXCameraState.Type.OPEN && state.error == null)
                            CameraAvailability.Open
                        else CameraAvailability.Closed(state.error?.code, state.error?.cause)
                    if (next != lastAvailability) {
                        lastAvailability = next
                        output.cameraAvailable(next == CameraAvailability.Open)
                        start.onAvailability(next)
                    }
                }
            }
    }

    companion object {
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

        /** Shared 16:9 resolution preference with a 1280 by 720 target and device fallback. */
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

        /** Bridges SDK completion and cancellation while preserving native failure causes. */
        fun ListenableFuture<*>.asCameraControlDeferred(
            operation: CameraControlOperation
        ): Deferred<Unit> {
            val result = CompletableDeferred<Unit>()
            fun fail(error: Exception) {
                val cause = if (error is ExecutionException) error.cause ?: error else error
                result.completeExceptionally(
                    PluginError.CameraControlError(operation, cause = cause)
                )
            }
            result.invokeOnCompletion { cause -> if (cause is CancellationException) cancel(false) }
            try {
                addListener(
                    {
                        if (result.isCompleted) return@addListener
                        try {
                            get()
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
