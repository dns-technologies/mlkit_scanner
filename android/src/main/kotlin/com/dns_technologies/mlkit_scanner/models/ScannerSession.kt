package com.dns_technologies.mlkit_scanner.models

import com.dns_technologies.mlkit_scanner.scanner.ScannerView
import com.dns_technologies.mlkit_scanner.scanner.models.InitialScannerParameters
import com.dns_technologies.mlkit_scanner.scanner.models.RecognizeVisorCropRect

/** Operations available for one scanner view lifecycle. */
internal interface ScannerSession {
    /** Returns whether this session currently owns an active camera. */
    fun isActive(): Boolean

    /** Starts the camera and applies optional initial scanner parameters. */
    suspend fun startCamera(parameters: InitialScannerParameters?)

    /** Resumes camera work after an explicit pause. */
    fun resumeCamera()

    /** Pauses camera work until explicitly resumed. */
    fun pauseCamera()

    /** Restores camera work when the host lifecycle resumes after a host pause. */
    fun onHostResume()

    /** Pauses camera work while the host lifecycle is paused. */
    fun onHostPause()

    /** Toggles the active camera torch. */
    fun toggleFlashLight()

    /** Starts barcode analysis with the requested delay between accepted frames. */
    fun startScan(periodMs: Int)

    /** Pauses barcode analysis without releasing camera resources. */
    fun pauseScan()

    /** Updates the delay between accepted barcode analysis attempts. */
    fun updateScanPeriod(periodMs: Int)

    /** Applies normalized zoom to the active camera. */
    fun setZoom(value: Float)

    /** Updates the frame region used for barcode recognition. */
    fun setCropArea(cropRect: RecognizeVisorCropRect)

    /** Cancels subscriptions and releases all resources owned by this session. */
    fun release()

    /** Returns whether this session owns the supplied scanner platform view. */
    fun owns(scannerView: ScannerView): Boolean
}
