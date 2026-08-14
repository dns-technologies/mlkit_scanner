package com.dns_technologies.mlkit_scanner.scanner

import android.view.View
import androidx.lifecycle.LifecycleOwner
import com.dns_technologies.mlkit_scanner.scanner.components.analyzer.ImageBarcodeAnalyzer
import com.dns_technologies.mlkit_scanner.scanner.components.camera.Camera
import com.dns_technologies.mlkit_scanner.scanner.components.camera.CameraFrame
import com.dns_technologies.mlkit_scanner.scanner.components.camera.OnError
import com.dns_technologies.mlkit_scanner.scanner.components.camera.OnInit
import com.dns_technologies.mlkit_scanner.scanner.models.Barcode
import com.dns_technologies.mlkit_scanner.scanner.models.RecognizeVisorCropRect
import com.dns_technologies.mlkit_scanner.scanner.models.ScanResultSubscription
import com.dns_technologies.mlkit_scanner.scanner.utils.ScanAreaCalculator
import java.util.concurrent.CopyOnWriteArraySet
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
    private val camera: Camera,
    private val analyzer: ImageBarcodeAnalyzer,
) {
    private var analysisExecutor: ExecutorService? = null
    @Volatile
    private var cropConfiguration = CropConfiguration()
    private val scanResultListeners = CopyOnWriteArraySet<OnScanResultListener>()

    /** Indicates whether incoming frames should be sent to the analyzer. */
    @Volatile
    var isScanActive = false
        private set

    /** Native preview view supplied by the camera adapter. */
    val previewView: View
        get() = camera.previewView

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

    /** Starts camera focus and metering around the visual scanner focus point. */
    fun focusOnCenter(resetDelayMs: Long, offsetX: Float, offsetY: Float) {
        camera.focusOnCenter(resetDelayMs, offsetX, offsetY)
    }

    /** Starts analysis with the configured analyzer component. */
    fun startScan(periodMs: Int) {
        analyzer.updatePeriod(periodMs)
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

    /** Subscribes to decoded scanner results and returns a cancellable subscription. */
    fun subscribeToScanResults(listener: OnScanResultListener): ScanResultSubscription {
        scanResultListeners += listener
        return ScanResultSubscription { scanResultListeners -= listener }
    }

    /** Updates scanner crop settings used for frame preparation. */
    fun setCropArea(cropRect: RecognizeVisorCropRect) {
        updateCropConfiguration { it.copy(scanArea = cropRect) }
    }

    /** Updates the ratio between the scanner view and the physical display. */
    fun setScale(widthScale: Double, heightScale: Double) {
        updateCropConfiguration {
            it.copy(scale = Pair(widthScale, heightScale))
        }
    }

    /** Applies normalized zoom to the active camera. */
    fun setZoom(value: Float) {
        camera.setZoom(value)
    }

    /** Releases scanner components and stops pending analysis work. */
    fun dispose() {
        isScanActive = false
        camera.dispose()
        analysisExecutor?.shutdownNow()
        analysisExecutor = null
        analyzer.dispose()
    }

    /** Processes a camera frame when scanning is active. */
    private fun analyzeFrame(frame: CameraFrame) {
        if (!isScanActive) return

        val configuration = cropConfiguration
        val cropRect = configuration.scanArea?.let { scanArea ->
            ScanAreaCalculator.calculate(frame, scanArea, configuration.scale)
        }
        analyzer.analyze(frame, cropRect)?.let(::emitScanResult)
    }

    /** Notifies all active listeners about a recognized scanner result. */
    private fun emitScanResult(result: Barcode) {
        scanResultListeners.forEach { listener ->
            listener.invoke(result)
        }
    }

    private fun updateCropConfiguration(
        transform: (CropConfiguration) -> CropConfiguration,
    ) {
        cropConfiguration = transform(cropConfiguration)
    }

    companion object {
        /** Default scale when scanner view size matches the display size. */
        private val DEFAULT_SCALE = Pair(1.0, 1.0)
    }

    private data class CropConfiguration(
        val scanArea: RecognizeVisorCropRect? = null,
        val scale: Pair<Double, Double> = DEFAULT_SCALE,
    )
}
