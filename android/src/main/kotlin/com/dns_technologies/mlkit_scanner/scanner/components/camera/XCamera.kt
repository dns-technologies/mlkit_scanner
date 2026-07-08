package com.dns_technologies.mlkit_scanner.scanner.components.camera

import android.content.Context
import android.util.Size
import android.view.View
import android.view.ViewGroup
import androidx.camera.core.Camera as AndroidXCamera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.TorchState
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.dns_technologies.mlkit_scanner.PluginError
import com.dns_technologies.mlkit_scanner.scanner.utils.ImageProxyNv21Converter
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/**
 * CameraX adapter that hides CameraX APIs behind [Camera].
 */
class XCamera(
    private val context: Context,
) : Camera {
    /** CameraX preview view rendered inside the scanner platform view. */
    override val previewView: View = PreviewView(context).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        scaleType = PreviewView.ScaleType.FILL_CENTER
    }

    /** CameraX provider used to bind and unbind. */
    private var cameraProvider: ProcessCameraProvider? = null

    /** Active CameraX camera instance. */
    private var camera: AndroidXCamera? = null

    /** Preview owned by this camera adapter. */
    private var preview: Preview? = null

    /** Image analysis owned by this camera adapter. */
    private var imageAnalysis: ImageAnalysis? = null

    /** Token of the active asynchronous CameraX start operation. */
    private var startToken: Any? = null

    /** Starts CameraX preview and analysis use cases for the provided lifecycle. */
    override fun start(
        lifecycleOwner: LifecycleOwner,
        analysisExecutor: ExecutorService,
        onFrame: OnCameraFrame,
        onInit: OnInit,
        onError: OnError,
    ) {
        if (isStarting() || isActive()) return

        val token = Any()
        startToken = token
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            if (startToken !== token) return@addListener
            try {
                cameraProvider = providerFuture.get()
                bindCamera(lifecycleOwner, analysisExecutor, onFrame)
                onInit.invoke()
            } catch (e: Exception) {
                onError.invoke(e)
            } finally {
                if (startToken === token) {
                    startToken = null
                }
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /** Returns true when a CameraX camera is currently bound. */
    override fun isActive(): Boolean = camera != null

    /** Toggles torch state for the active CameraX camera. */
    override fun toggleFlashLight() {
        val activeCamera = camera ?: return
        if (!activeCamera.cameraInfo.hasFlashUnit()) {
            throw PluginError.DeviceHasNotFlash
        }

        val isTorchEnabled = activeCamera.cameraInfo.torchState.value == TorchState.ON
        activeCamera.cameraControl.enableTorch(!isTorchEnabled)
    }

    /** Starts CameraX focus and metering around the provided preview offsets. */
    override fun focusOnCenter(resetDelayMs: Long, offsetX: Float, offsetY: Float) {
        val activeCamera = camera ?: return
        val preview = previewView as PreviewView
        if (preview.width == 0 || preview.height == 0) return

        val focusPoint = preview.meteringPointFactory.createPoint(
            preview.width / 2F + offsetX,
            preview.height / 2F + offsetY,
        )
        val focusActionBuilder = FocusMeteringAction.Builder(
            focusPoint,
            FocusMeteringAction.FLAG_AF,
        )
        if (resetDelayMs > 0L) {
            focusActionBuilder.setAutoCancelDuration(resetDelayMs, TimeUnit.MILLISECONDS)
        }

        activeCamera.cameraControl.startFocusAndMetering(focusActionBuilder.build())
    }

    /** Applies normalized linear zoom to the active CameraX camera. */
    override fun setZoom(value: Float) {
        val activeCamera = camera ?: return
        if (activeCamera.cameraInfo.zoomState.value == null) {
            throw PluginError.DeviceHasNotZoom
        }
        activeCamera.cameraControl.setLinearZoom(value.coerceIn(0.0F, 1.0F))
    }

    /** Releases CameraX bindings owned by this adapter. */
    override fun dispose() {
        startToken = null
        unbindViews()
        cameraProvider = null
        camera = null
    }

    /** Returns true while CameraX provider initialization or binding is in progress. */
    private fun isStarting(): Boolean = startToken != null

    /** Binds CameraX preview and analysis to the scanner lifecycle. */
    private fun bindCamera(
        lifecycleOwner: LifecycleOwner,
        analysisExecutor: ExecutorService,
        onFrame: OnCameraFrame,
    ) {
        val provider = cameraProvider ?: return
        val preview = createPreview()
        val imageAnalysis = createImageAnalysis(analysisExecutor, onFrame)

        unbindViews()
        camera = provider.bindToLifecycle(
            lifecycleOwner,
            CameraSelector.DEFAULT_BACK_CAMERA,
            preview,
            imageAnalysis,
        )
        this.preview = preview
        this.imageAnalysis = imageAnalysis
    }

    /** Creates the CameraX preview. */
    private fun createPreview(): Preview = Preview.Builder()
        .setResolutionSelector(DEFAULT_RESOLUTION_SELECTOR)
        .build()
        .also {
            it.surfaceProvider = (previewView as PreviewView).surfaceProvider
        }

    /** Creates the CameraX image analysis. */
    private fun createImageAnalysis(
        analysisExecutor: ExecutorService,
        onFrame: OnCameraFrame,
    ): ImageAnalysis = ImageAnalysis.Builder()
        .setResolutionSelector(DEFAULT_RESOLUTION_SELECTOR)
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .build()
        .also { imageAnalysis ->
            imageAnalysis.setAnalyzer(analysisExecutor) { image ->
                try {
                    onFrame.invoke(ImageProxyNv21Converter.convert(image))
                } finally {
                    image.close()
                }
            }
        }

    /** Unbinds only created by this adapter views. */
    private fun unbindViews() {
        val ownedPreview = preview
        val ownedImageAnalysis = imageAnalysis

        if (ownedPreview != null) cameraProvider?.unbind(ownedPreview)
        if (ownedImageAnalysis != null) cameraProvider?.unbind(ownedImageAnalysis)

        preview = null
        imageAnalysis = null
    }

    private companion object {
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
