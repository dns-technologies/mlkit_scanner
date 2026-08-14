package com.dns_technologies.mlkit_scanner.models

import com.dns_technologies.mlkit_scanner.scanner.ScannerView
import com.dns_technologies.mlkit_scanner.scanner.models.InitialScannerParameters
import com.dns_technologies.mlkit_scanner.scanner.models.RecognizeVisorCropRect

/** Operations available for one scanner view lifecycle. */
internal interface ScannerSession {
    fun isActive(): Boolean
    suspend fun startCamera(parameters: InitialScannerParameters?)
    fun resumeCamera()
    fun pauseCamera()
    fun onHostResume()
    fun onHostPause()
    fun toggleFlashLight()
    fun startScan(periodMs: Int)
    fun pauseScan()
    fun updateScanPeriod(periodMs: Int)
    fun setZoom(value: Float)
    fun setCropArea(cropRect: RecognizeVisorCropRect)
    fun release()
    fun owns(scannerView: ScannerView): Boolean
}
