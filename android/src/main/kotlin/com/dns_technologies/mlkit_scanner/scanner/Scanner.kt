package com.dns_technologies.mlkit_scanner.scanner

import android.graphics.Rect
import androidx.lifecycle.LifecycleOwner
import com.dns_technologies.mlkit_scanner.scanner.components.analyzer.ImageBarcodeAnalyzer
import com.dns_technologies.mlkit_scanner.scanner.components.camera.Camera
import com.dns_technologies.mlkit_scanner.scanner.components.camera.OnError
import com.dns_technologies.mlkit_scanner.scanner.components.camera.OnInit
import com.dns_technologies.mlkit_scanner.scanner.models.RecognizeVisorCropRect
import com.dns_technologies.mlkit_scanner.scanner.models.images.AnalysingImage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Listener that receives decoded scanner results. */
typealias OnScanResultListener = (result: String) -> Unit

/** Owns scanner behavior independent from Flutter platform view plumbing. */
class Scanner(
    val camera: Camera,
    private val analyzer: ImageBarcodeAnalyzer,
) {
    private var analysisExecutor: ExecutorService? = null
    private var scanArea: RecognizeVisorCropRect = RecognizeVisorCropRect()
    private var widgetWidthScale: Double = 1.0
    private var widgetHeightScale: Double = 1.0
    private val scanResultListeners = mutableSetOf<OnScanResultListener>()

    /** Indicates whether incoming frames should be sent to the analyzer. */
    var isScanActive = false
        private set
    private var isAnalyzerInitialized = false

    /** Starts the delegated camera and wires it to common frame handling. */
    fun startCamera(lifecycleOwner: LifecycleOwner, onInit: OnInit, onError: OnError) {
        camera.start(
            lifecycleOwner = lifecycleOwner,
            analysisExecutor = ensureAnalysisExecutor(),
            onFrame = this::analyzeFrame,
            onInit = onInit,
            onError = onError,
        )
    }

    /** Returns true when the scanner camera is active. */
    fun isActive(): Boolean = camera.isActive()

    /** Toggles torch state through the active camera component. */
    fun toggleFlashLight() {
        camera.toggleFlashLight()
    }

    /** Starts analysis with the configured analyzer component. */
    fun startScan(periodMs: Int) {
        if (!isAnalyzerInitialized) {
            analyzer.init(periodMs)
            isAnalyzerInitialized = true
        } else if (analyzer.analyzePeriodMs != periodMs) {
            analyzer.updatePeriod(periodMs)
        }
        isScanActive = true
    }

    /** Pauses frame analysis without releasing analyzer resources. */
    fun pauseScan() {
        isScanActive = false
    }

    /** Updates the delay between analyzer attempts. */
    fun updateScanPeriod(periodMs: Int) {
        analyzer.updatePeriod(periodMs)
    }

    /** Subscribes to decoded scanner results and returns an unsubscribe callback. */
    fun observeScanResults(listener: OnScanResultListener): () -> Unit {
        scanResultListeners += listener
        return { scanResultListeners -= listener }
    }

    /** Updates scanner crop settings used for focus, visor UI and frame preparation. */
    fun setCropArea(cropRect: RecognizeVisorCropRect) {
        scanArea = cropRect
    }

    /** Updates the ratio between the scanner widget and the physical display. */
    fun setWidgetScale(widthScale: Double, heightScale: Double) {
        widgetWidthScale = widthScale
        widgetHeightScale = heightScale
    }

    /** Applies normalized zoom to the active camera. */
    fun setZoom(value: Float) {
        camera.setZoom(value)
    }

    /** Releases scanner components and stops pending analysis work. */
    fun releaseCamera() {
        analyzer.dispose()
        camera.release()
        analysisExecutor?.shutdownNow()
        analysisExecutor = null
    }

    /** Returns an active single-thread executor used for frame analysis. */
    private fun ensureAnalysisExecutor(): ExecutorService {
        val activeExecutor = analysisExecutor
        if (activeExecutor != null && !activeExecutor.isShutdown) return activeExecutor

        return Executors.newSingleThreadExecutor().also {
            analysisExecutor = it
        }
    }

    /** Processes a camera frame when scanning is active. */
    private fun analyzeFrame(image: AnalysingImage) {
        if (!isScanActive) return

        cropToScanArea(image)
        analyzer.analyze(image)?.let(::emitScanResult)
    }

    /** Crops the frame to the configured scanner area when needed. */
    private fun cropToScanArea(image: AnalysingImage) {
        if (!shouldCropScanArea()) return

        buildScanAreaRect(image).let { rect ->
            image.crop(rect.left, rect.top, rect.right, rect.bottom)
        }
    }

    /** Returns true when scanner geometry requires frame cropping. */
    private fun shouldCropScanArea(): Boolean =
        scanArea.shouldCrop() || widgetWidthScale != 1.0 || widgetHeightScale != 1.0

    /** Builds the image-space rectangle that should be sent to the analyzer. */
    private fun buildScanAreaRect(image: AnalysingImage): Rect {
        val resultScaleX = widgetWidthScale * scanArea.scaleWidth
        val resultScaleY = widgetHeightScale * scanArea.scaleHeight * HEIGHT_COMPENSATION
        val (widthCrop, heightCrop) = when (image.rotationDegree) {
            90, 270 -> Pair(resultScaleY, resultScaleX)
            else -> Pair(resultScaleX, resultScaleY)
        }
        return Rect(0, 0, image.width, image.height).apply {
            inset(
                (image.width * (1 - widthCrop) / 2).toInt(),
                (image.height * (1 - heightCrop) / 2).toInt(),
            )
            offset(
                (calculateScanAreaOffsetX(image) * widgetHeightScale).toInt(),
                (calculateScanAreaOffsetY(image) * widgetWidthScale).toInt(),
            )
        }
    }

    /** Calculates horizontal scan-area offset for the current image rotation. */
    private fun calculateScanAreaOffsetX(image: AnalysingImage): Int =
        when (image.rotationDegree) {
            0 -> ((image.width / 2) * scanArea.centerOffsetX).toInt()
            90 -> ((image.width / 2) * scanArea.centerOffsetY).toInt()
            180 -> -((image.width / 2) * scanArea.centerOffsetX).toInt()
            else -> -((image.width / 2) * scanArea.centerOffsetY).toInt()
        }

    /** Calculates vertical scan-area offset for the current image rotation. */
    private fun calculateScanAreaOffsetY(image: AnalysingImage): Int =
        when (image.rotationDegree) {
            0 -> (image.height / 2 * scanArea.centerOffsetY).toInt()
            90 -> -((image.height / 2) * scanArea.centerOffsetX).toInt()
            180 -> -((image.height / 2) * scanArea.centerOffsetY).toInt()
            else -> (image.height / 2 * scanArea.centerOffsetX).toInt()
        }

    /** Notifies all active listeners about a recognized scanner result. */
    private fun emitScanResult(result: String) {
        scanResultListeners.toList().forEach { listener ->
            listener.invoke(result)
        }
    }

    companion object {
        /** Keeps parity with the existing visor-to-camera height mapping. */
        const val HEIGHT_COMPENSATION = 1.2
    }
}
