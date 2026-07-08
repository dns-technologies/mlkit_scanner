package com.dns_technologies.mlkit_scanner.models

import com.dns_technologies.mlkit_scanner.scanner.ScannerView
import com.dns_technologies.mlkit_scanner.scanner.models.Barcode
import com.dns_technologies.mlkit_scanner.scanner.models.InitialScannerParameters
import com.dns_technologies.mlkit_scanner.scanner.models.RecognizeVisorCropRect
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Owns one scanner view lifecycle and its scan-result subscription.
 *
 * @property scannerView Platform view managed by this scanner session.
 */
internal class ScannerSession(
    private val scannerView: ScannerView,
    private val onScanResult: (Barcode) -> Unit,
) {
    private var unsubscribeFromScanResults: (() -> Unit)? = scannerView.subscribeToScanResults(onScanResult)
    private var lastPauseReason: PauseReason? = null

    /** Active scanner view, or null when the camera is not currently bound. */
    private val activeScannerView: ScannerView?
        get() = scannerView.takeIf { it.isActive() }

    /** Returns true when this session owns an active camera binding. */
    fun isActive(): Boolean = scannerView.isActive()

    /** Starts the session camera and suspends until initialization completes. */
    suspend fun startCamera(parameters: InitialScannerParameters?) {
        suspendCancellableCoroutine { continuation ->
            scannerView.startCamera(
                initialZoom = parameters?.zoom?.toFloat(),
                initialCropRect = parameters?.cropRect,
                onReady = {
                    if (continuation.isActive) continuation.resume(Unit)
                },
                onError = { error ->
                    if (continuation.isActive) continuation.resumeWithException(error)
                },
            )
        }
    }

    /** Resumes camera work after a manual pause or host lifecycle resume. */
    fun resumeCamera() {
        scannerView.resumeCamera()

        lastPauseReason = null
    }

    /** Pauses camera work because of an explicit Dart-side request. */
    fun pauseCamera() {
        scannerView.pauseCamera()

        lastPauseReason = PauseReason.MANUAL
    }

    /** Restores camera work when the host resumes after a host-driven pause. */
    fun onHostResume() {
        if (lastPauseReason == PauseReason.HOST) {
            resumeCamera()
        }
    }

    /** Pauses camera work when the Android host lifecycle moves to background. */
    fun onHostPause() {
        val activeScannerView = activeScannerView ?: return
        activeScannerView.pauseCamera()

        lastPauseReason = PauseReason.HOST
    }

    /** Toggles camera torch for the active scanner view. */
    fun toggleFlashLight() {
        scannerView.toggleFlashLight()
    }

    /** Starts frame analysis on the active scanner view. */
    fun startScan(periodMs: Int) {
        scannerView.startScan(periodMs)
    }

    /** Pauses frame analysis on the scanner view. */
    fun pauseScan() {
        scannerView.pauseScan()
    }

    /** Updates frame analysis delay. */
    fun updateScanPeriod(periodMs: Int) {
        scannerView.updateScanPeriod(periodMs)
    }

    /** Updates camera zoom for the active scanner view. */
    fun setZoom(value: Float) {
        scannerView.setZoom(value)
    }

    /** Updates scanner crop area. */
    fun setCropArea(cropRect: RecognizeVisorCropRect) {
        scannerView.setCropArea(cropRect)
    }

    /** Releases subscription and native resources owned by this session. */
    fun release() {
        unsubscribeFromScanResults?.invoke()
        unsubscribeFromScanResults = null
        scannerView.dispose()
        lastPauseReason = null
    }

    /** Returns true when the provided view is the platform view owned by this session. */
    fun owns(scannerView: ScannerView): Boolean = scannerView === this.scannerView

    private enum class PauseReason {
        MANUAL,
        HOST,
    }
}
