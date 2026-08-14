package com.dns_technologies.mlkit_scanner.models

import com.dns_technologies.mlkit_scanner.PluginError
import com.dns_technologies.mlkit_scanner.scanner.ScannerView
import com.dns_technologies.mlkit_scanner.scanner.models.Barcode
import com.dns_technologies.mlkit_scanner.scanner.models.InitialScannerParameters
import com.dns_technologies.mlkit_scanner.scanner.models.RecognizeVisorCropRect
import com.dns_technologies.mlkit_scanner.scanner.models.ScanResultSubscription
import kotlinx.coroutines.CompletableDeferred

/** Production scanner session backed by an Android scanner view. */
internal class ScannerSessionImpl(
    private val scannerView: ScannerView,
    onScanResult: (Barcode) -> Unit,
) : ScannerSession {
    private var scanSubscription: ScanResultSubscription? = scannerView.subscribeToScanResults(onScanResult)
    private var cameraInitialization: CompletableDeferred<Unit>? = null
    private var lastPauseReason: PauseReason? = null

    override fun isActive(): Boolean = scannerView.isActive()

    override suspend fun startCamera(parameters: InitialScannerParameters?) {
        if (scanSubscription == null) throw PluginError.CameraSessionDisposed
        if (scannerView.isActive()) return

        initializeCamera(parameters).await()
    }

    override fun resumeCamera() {
        scannerView.resumeCamera()
        lastPauseReason = null
    }

    override fun pauseCamera() {
        scannerView.pauseCamera()
        lastPauseReason = PauseReason.MANUAL
    }

    override fun onHostResume() {
        if (lastPauseReason == PauseReason.HOST) resumeCamera()
    }

    override fun onHostPause() {
        if (!scannerView.isActive()) return

        scannerView.pauseCamera()
        lastPauseReason = PauseReason.HOST
    }

    override fun toggleFlashLight() = scannerView.toggleFlashLight()
    override fun startScan(periodMs: Int) = scannerView.startScan(periodMs)
    override fun pauseScan() = scannerView.pauseScan()
    override fun updateScanPeriod(periodMs: Int) = scannerView.updateScanPeriod(periodMs)
    override fun setZoom(value: Float) = scannerView.setZoom(value)
    override fun setCropArea(cropRect: RecognizeVisorCropRect) = scannerView.setCropArea(cropRect)

    override fun release() {
        val subscription = scanSubscription ?: return
        scanSubscription = null

        cameraInitialization?.completeExceptionally(PluginError.CameraSessionDisposed)
        subscription.cancel()
        scannerView.dispose()
        lastPauseReason = null
    }

    override fun owns(scannerView: ScannerView): Boolean = scannerView === this.scannerView

    private fun initializeCamera(parameters: InitialScannerParameters?): CompletableDeferred<Unit> {
        cameraInitialization?.let { return it }

        val initialization = CompletableDeferred<Unit>()
        cameraInitialization = initialization
        initialization.invokeOnCompletion { cameraInitialization = null }

        fun fail(error: Throwable) {
            if (initialization.completeExceptionally(error)) release()
        }

        try {
            scannerView.startCamera(
                initialZoom = parameters?.zoom?.toFloat(),
                initialCropRect = parameters?.cropRect,
                onReady = { initialization.complete(Unit) },
                onError = ::fail,
            )
        } catch (error: Throwable) {
            fail(error)
        }

        return initialization
    }

    private enum class PauseReason {
        MANUAL,
        HOST,
    }
}
