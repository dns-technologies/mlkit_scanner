package com.dns_technologies.mlkit_scanner.scanner.components.camera

import android.content.Context
import android.graphics.ImageFormat
import android.util.Size
import android.view.View
import android.view.ViewGroup
import androidx.camera.core.Camera as AndroidXCamera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.TorchState
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.dns_technologies.mlkit_scanner.scanner.components.camera.exceptions.HasNoFlashUnitException
import com.dns_technologies.mlkit_scanner.scanner.components.camera.exceptions.ZoomNotSupportedException
import com.dns_technologies.mlkit_scanner.scanner.models.images.NV21AnalysingImage
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/**
 * CameraX adapter that hides CameraX APIs behind [Camera].
 */
class XCamera(
    private val context: Context,
) : Camera {
    override val previewView: View = PreviewView(context).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        scaleType = PreviewView.ScaleType.FILL_CENTER
    }

    /** CameraX provider used to bind and unbind use cases. */
    private var cameraProvider: ProcessCameraProvider? = null

    /** Active CameraX camera instance. */
    private var camera: AndroidXCamera? = null

    /** Invalidates pending asynchronous CameraX initialization callbacks after release. */
    private var cameraSessionId = 0

    override fun start(
        lifecycleOwner: LifecycleOwner,
        analysisExecutor: ExecutorService,
        onFrame: OnCameraFrame,
        onInit: OnInit,
        onError: OnError,
    ) {
        val sessionId = ++cameraSessionId
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            if (sessionId != cameraSessionId) return@addListener
            try {
                cameraProvider = providerFuture.get()
                bindCamera(lifecycleOwner, analysisExecutor, onFrame)
                onInit.invoke()
            } catch (e: Exception) {
                onError.invoke(e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    override fun isActive(): Boolean = camera != null

    override fun toggleFlashLight() {
        val activeCamera = camera ?: return
        if (!activeCamera.cameraInfo.hasFlashUnit()) throw HasNoFlashUnitException()

        val isTorchEnabled = activeCamera.cameraInfo.torchState.value == TorchState.ON
        activeCamera.cameraControl.enableTorch(!isTorchEnabled)
    }

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

    override fun setZoom(value: Float) {
        val activeCamera = camera ?: return
        if (activeCamera.cameraInfo.zoomState.value == null) {
            throw ZoomNotSupportedException()
        }
        activeCamera.cameraControl.setLinearZoom(value.coerceIn(0.0F, 1.0F))
    }

    override fun release() {
        cameraSessionId++
        cameraProvider?.unbindAll()
        cameraProvider = null
        camera = null
    }

    /** Binds CameraX preview and analysis to the scanner lifecycle. */
    private fun bindCamera(
        lifecycleOwner: LifecycleOwner,
        analysisExecutor: ExecutorService,
        onFrame: OnCameraFrame,
    ) {
        val provider = cameraProvider ?: return
        val preview = createPreview()
        val imageAnalysis = createImageAnalysis(analysisExecutor, onFrame)

        provider.unbindAll()
        camera = provider.bindToLifecycle(
            lifecycleOwner,
            CameraSelector.DEFAULT_BACK_CAMERA,
            preview,
            imageAnalysis,
        )
    }

    /** Creates the CameraX preview. */
    private fun createPreview(): Preview = Preview.Builder()
        .setTargetResolution(DEFAULT_TARGET_RESOLUTION)
        .build()
        .also {
            it.setSurfaceProvider((previewView as PreviewView).surfaceProvider)
        }

    /** Creates the CameraX image analysis. */
    private fun createImageAnalysis(
        analysisExecutor: ExecutorService,
        onFrame: OnCameraFrame,
    ): ImageAnalysis = ImageAnalysis.Builder()
        .setTargetResolution(DEFAULT_TARGET_RESOLUTION)
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .build()
        .also { imageAnalysis ->
            imageAnalysis.setAnalyzer(analysisExecutor) { image ->
                analyzeImage(image, onFrame)
            }
        }

    /** Converts a CameraX frame and sends it to the common scanner layer. */
    private fun analyzeImage(image: ImageProxy, onFrame: OnCameraFrame) {
        try {
            onFrame.invoke(
                NV21AnalysingImage(
                    image.toNv21ByteArray(),
                    Size(image.width, image.height),
                    ImageFormat.NV21,
                    image.imageInfo.rotationDegrees,
                )
            )
        } finally {
            image.close()
        }
    }

    /** Converts the CameraX YUV image into the NV21 format expected by the analyzer. */
    private fun ImageProxy.toNv21ByteArray(): ByteArray {
        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]

        val ySize = width * height
        val uvSize = width * height / 2
        val nv21 = ByteArray(ySize + uvSize)

        var outputOffset = 0
        for (row in 0 until height) {
            val inputOffset = row * yPlane.rowStride
            for (col in 0 until width) {
                nv21[outputOffset++] = yPlane.buffer.get(inputOffset + col * yPlane.pixelStride)
            }
        }

        val chromaHeight = height / 2
        val chromaWidth = width / 2
        for (row in 0 until chromaHeight) {
            val uRowOffset = row * uPlane.rowStride
            val vRowOffset = row * vPlane.rowStride
            for (col in 0 until chromaWidth) {
                nv21[outputOffset++] = vPlane.buffer.get(vRowOffset + col * vPlane.pixelStride)
                nv21[outputOffset++] = uPlane.buffer.get(uRowOffset + col * uPlane.pixelStride)
            }
        }

        return nv21
    }

    private companion object {
        /** Default CameraX target resolution used by preview and analysis. */
        val DEFAULT_TARGET_RESOLUTION = Size(720, 1280)
    }
}
