package com.dns_technologies.mlkit_scanner.scanner

import androidx.lifecycle.LifecycleOwner
import com.dns_technologies.mlkit_scanner.scanner.components.analyzer.ImageBarcodeAnalyzer
import com.dns_technologies.mlkit_scanner.scanner.components.camera.Camera
import com.dns_technologies.mlkit_scanner.scanner.components.camera.OnError
import com.dns_technologies.mlkit_scanner.scanner.components.camera.OnInit
import com.dns_technologies.mlkit_scanner.scanner.models.Barcode
import com.dns_technologies.mlkit_scanner.scanner.models.RecognizeVisorCropRect
import com.dns_technologies.mlkit_scanner.scanner.models.images.AnalysingImage
import com.dns_technologies.mlkit_scanner.scanner.utils.ScanAreaCropper
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Listener that receives decoded scanner results. */
typealias OnScanResultListener = (result: Barcode) -> Unit

/**
 * Owns scanner behavior independent from Flutter platform view plumbing.
 *
 * @property camera Camera adapter used for preview, focus, flash and zoom.
 */
class Scanner(
    val camera: Camera,
    private val analyzer: ImageBarcodeAnalyzer,
) {
    private var analysisExecutor: ExecutorService? = null
    private var scanArea: RecognizeVisorCropRect? = null
    private var scale: Pair<Double, Double> = DEFAULT_SCALE
    private val scanResultListeners = mutableSetOf<OnScanResultListener>()

    /** Indicates whether incoming frames should be sent to the analyzer. */
    var isScanActive = false
        private set
    private var isAnalyzerInitialized = false

    /** Starts the delegated camera and wires it to common frame handling. */
    fun startCamera(lifecycleOwner: LifecycleOwner, onInit: OnInit, onError: OnError) {
        val executor = analysisExecutor
            ?.takeUnless { it.isShutdown }
            ?: Executors.newSingleThreadExecutor().also { analysisExecutor = it }

        camera.start(
            lifecycleOwner = lifecycleOwner,
            analysisExecutor = executor,
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
    fun subscribeToScanResults(listener: OnScanResultListener): () -> Unit {
        scanResultListeners += listener
        return { scanResultListeners -= listener }
    }

    /** Updates scanner crop settings used for frame preparation. */
    fun setCropArea(cropRect: RecognizeVisorCropRect) {
        scanArea = cropRect
    }

    /** Updates the ratio between the scanner widget and the physical display. */
    fun setScale(widthScale: Double, heightScale: Double) {
        scale = Pair(widthScale, heightScale)
    }

    /** Applies normalized zoom to the active camera. */
    fun setZoom(value: Float) {
        camera.setZoom(value)
    }

    /** Releases scanner components and stops pending analysis work. */
    fun dispose() {
        camera.dispose()
        analysisExecutor?.shutdownNow()
        analysisExecutor = null
        analyzer.dispose()
    }

    /** Processes a camera frame when scanning is active. */
    private fun analyzeFrame(image: AnalysingImage) {
        if (!isScanActive) return

        cropToScanArea(image)
        analyzer.analyze(image)?.let(::emitScanResult)
    }

    /** Crops the frame when a scanner area has been configured. */
    private fun cropToScanArea(image: AnalysingImage) {
        val activeScanArea = scanArea ?: return
        ScanAreaCropper.crop(image, activeScanArea, scale)
    }

    /** Notifies all active listeners about a recognized scanner result. */
    private fun emitScanResult(result: Barcode) {
        scanResultListeners.toList().forEach { listener ->
            listener.invoke(result)
        }
    }

    companion object {
        /** Default scale when scanner widget size matches the display size. */
        private val DEFAULT_SCALE = Pair(1.0, 1.0)
    }
}
