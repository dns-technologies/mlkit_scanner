package com.dns_technologies.mlkit_scanner.models

import com.dns_technologies.mlkit_scanner.scanner.ScannerView
import com.dns_technologies.mlkit_scanner.scanner.models.Barcode

/**
 * Owns one scanner view lifecycle and its scan-result subscription.
 *
 * @property scannerView Platform view managed by this scanner session.
 */
internal class ScannerSession(
    val scannerView: ScannerView,
    private val onScanResult: (Barcode) -> Unit,
) {
    private var unsubscribeFromScanResults: (() -> Unit)? = scannerView.subscribeToScanResults(onScanResult)
    private var lastPauseReason: PauseReason? = null

    /** Active scanner view, or null when the camera is not currently bound. */
    val activeScannerView: ScannerView?
        get() = scannerView.takeIf { it.isActive() }

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
