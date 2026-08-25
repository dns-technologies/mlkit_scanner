package com.dns_technologies.mlkit_scanner.models

import android.content.Context
import com.dns_technologies.mlkit_scanner.scanner.ScannerView
import com.dns_technologies.mlkit_scanner.scanner.models.RecognizeVisorCropRect

/** Operations available for the single scanner session shared by platform views. */
internal interface ScannerSession {
    /** Creates and registers a platform view. */
    fun createView(context: Context, viewId: Int): ScannerView

    /** Starts the shared camera and applies optional initial zoom and recognition area. */
    suspend fun startCamera(
        viewId: Int,
        initialZoom: Double?,
        initialCropRect: RecognizeVisorCropRect?,
    )

    /** Resumes the shared camera after an explicit pause. */
    fun resumeCamera()

    /** Pauses the shared camera without releasing it. */
    fun pauseCamera()

    /** Restores shared camera work when the host lifecycle resumes. */
    fun onHostResume()

    /** Pauses shared camera work while the host lifecycle is paused. */
    fun onHostPause()

    /** Toggles the shared camera torch. */
    fun toggleFlashLight()

    /** Starts barcode analysis with the requested delay. */
    fun startScan(periodMs: Int)

    /** Pauses barcode analysis without releasing the shared camera. */
    fun pauseScan()

    /** Updates the shared delay between barcode analysis attempts. */
    fun updateScanPeriod(periodMs: Int)

    /** Applies normalized zoom to the shared camera. */
    fun setZoom(value: Float)

    /** Updates the shared barcode recognition region. */
    fun setCropArea(cropRect: RecognizeVisorCropRect)

    /** Removes one platform view registration. */
    fun disposeView(viewId: Int)

    /** Cancels subscriptions and releases all shared scanner resources. */
    fun release()
}
