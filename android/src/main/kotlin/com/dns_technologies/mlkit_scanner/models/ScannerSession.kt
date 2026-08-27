package com.dns_technologies.mlkit_scanner.models

import android.content.Context
import androidx.lifecycle.Lifecycle
import com.dns_technologies.mlkit_scanner.scanner.ScannerView
import com.dns_technologies.mlkit_scanner.scanner.models.RecognizeVisorCropRect

/** Operations available for the single scanner session shared by platform views. */
internal interface ScannerSession {
    /** Creates and registers a platform view. */
    fun createView(context: Context, viewId: Int): ScannerView

    /** Starts the shared camera and records initial controls owned by this platform view. */
    suspend fun startCamera(
        viewId: Int,
        initialZoom: Double?,
        initialCropRect: RecognizeVisorCropRect?,
        initialFlashEnabled: Boolean?,
    )

    /** Selects one registered platform view and resumes its retained camera and scan state. */
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

    /** Updates the requesting view's normalized zoom and applies it when active. */
    suspend fun setZoom(viewId: Int, value: Float)

    /** Updates the requesting view's barcode recognition region. */
    fun setCropArea(viewId: Int, cropRect: RecognizeVisorCropRect)

    /** Removes one platform view registration. */
    fun disposeView(viewId: Int)

    /** Cancels subscriptions and releases all shared scanner resources. */
    fun release()
}
