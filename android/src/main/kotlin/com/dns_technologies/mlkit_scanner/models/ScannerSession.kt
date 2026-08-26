package com.dns_technologies.mlkit_scanner.models

import android.content.Context
import androidx.lifecycle.Lifecycle
import com.dns_technologies.mlkit_scanner.scanner.ScannerView
import com.dns_technologies.mlkit_scanner.scanner.models.RecognizeVisorCropRect

/** Operations available for the single scanner session shared by platform views. */
internal interface ScannerSession {
    /** Creates and registers a platform view. */
    fun createView(context: Context, viewId: Int): ScannerView

    /** Starts the shared camera once without overriding controls retained for later views. */
    suspend fun startCamera(
        viewId: Int,
        initialZoom: Double?,
        initialCropRect: RecognizeVisorCropRect?,
    )

    /** Resumes camera work requested by one registered platform view. */
    fun resumeCamera(viewId: Int)

    /** Pauses camera work requested by one platform view without releasing the shared camera. */
    fun pauseCamera(viewId: Int)

    /** Observes the current Activity lifecycle that gates shared camera work. */
    fun attachHostLifecycle(lifecycle: Lifecycle)

    /** Stops observing the previous Activity lifecycle and pauses shared camera work. */
    fun detachHostLifecycle()

    /** Toggles the shared camera torch and waits for CameraX completion. */
    suspend fun toggleFlashLight()

    /** Starts sampled barcode analysis with a cooldown between successful results. */
    fun startScan(viewId: Int, periodMs: Int)

    /** Pauses barcode analysis requested by one platform view. */
    fun pauseScan(viewId: Int)

    /** Updates the shared cooldown applied after successful barcode recognition. */
    fun updateScanPeriod(periodMs: Int)

    /** Applies normalized zoom and waits for CameraX completion. */
    suspend fun setZoom(value: Float)

    /** Updates the initialized shared camera barcode recognition region. */
    fun setCropArea(cropRect: RecognizeVisorCropRect)

    /** Removes one platform view registration. */
    fun disposeView(viewId: Int)

    /** Cancels subscriptions and releases all shared scanner resources. */
    fun release()
}
