package com.dns_technologies.mlkit_scanner.models

import android.content.Context
import androidx.lifecycle.Lifecycle
import com.dns_technologies.mlkit_scanner.scanner.ScannerView
import com.dns_technologies.mlkit_scanner.scanner.models.RecognizeVisorCropRect

/** Operations available for the single scanner session shared by platform views. */
internal interface ScannerSession {
    /** Creates the native platform view without assigning camera ownership. */
    fun createView(
        context: Context,
        viewId: Int,
        initialZoomRatio: Double?,
        initialCropRect: RecognizeVisorCropRect?,
        initialFlashEnabled: Boolean?,
    ): ScannerView

    /**
     * Selects one view, awaits that view's reusable initialization, then applies its retained state
     * only if it still owns the camera.
     */
    suspend fun captureCamera(
        viewId: Int,
        requestCameraPermission: suspend () -> Boolean,
    )

    /** Releases camera ownership only when it is still held by the referenced view. */
    fun releaseCamera(viewId: Int)

    /** Resumes retained camera intent without changing which view owns the camera. */
    fun resumeCamera(viewId: Int)

    /** Pauses camera work requested by one platform view without releasing the shared camera. */
    fun pauseCamera(viewId: Int)

    /** Observes the current Activity lifecycle that gates shared camera work. */
    fun attachHostLifecycle(lifecycle: Lifecycle)

    /** Stops observing the previous Activity lifecycle and pauses shared camera work. */
    fun detachHostLifecycle()

    /** Toggles the requesting view's torch state and applies it when that view is active. */
    suspend fun toggleFlashLight(viewId: Int)

    /** Starts sampled barcode analysis with a cooldown between successful results. */
    fun startScan(viewId: Int, periodMs: Int)

    /** Pauses barcode analysis requested by one platform view. */
    fun pauseScan(viewId: Int)

    /** Updates the requesting view's cooldown applied after successful recognition. */
    fun updateScanPeriod(viewId: Int, periodMs: Int)

    /** Updates the requesting view's absolute zoom ratio and applies it when active. */
    suspend fun setZoomRatio(viewId: Int, value: Float)

    /** Updates the requesting view's barcode recognition region. */
    fun setCropArea(viewId: Int, cropRect: RecognizeVisorCropRect)

    /** Cancels subscriptions and releases all shared scanner resources. */
    fun release()
}
