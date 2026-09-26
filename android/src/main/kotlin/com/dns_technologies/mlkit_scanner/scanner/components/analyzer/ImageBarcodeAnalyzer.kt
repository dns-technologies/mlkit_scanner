package com.dns_technologies.mlkit_scanner.scanner.components.analyzer

import com.dns_technologies.mlkit_scanner.scanner.components.analyzer.optimization.FrameAnalysisGate
import com.dns_technologies.mlkit_scanner.scanner.components.camera.CameraFrame
import com.dns_technologies.mlkit_scanner.scanner.components.camera.Rect
import com.dns_technologies.mlkit_scanner.scanner.models.Barcode

/**
 * Base class for barcode analyzers independent of a concrete image-analysis library.
 *
 * Provides time-based frame throttling, exclusive frame processing and thread-safe resource
 * disposal to every implementation.
 *
 * @param currentTimeMs Monotonic clock used by the analysis throttle.
 */
abstract class ImageBarcodeAnalyzer
protected constructor(currentTimeMs: () -> Long = android.os.SystemClock::elapsedRealtime) {
    /** Monotonic cooldown gate shared by all recognition attempts. */
    private val frameAnalysisGate = FrameAnalysisGate(0, currentTimeMs)
    /** Protects analysis ownership and deferred disposal decisions. */
    private val lifecycleLock = Any()
    // Guarded by lifecycleLock. Recognition and resource cleanup never hold this lock.
    /** Exclusive recognition ownership, guarded by [lifecycleLock]. */
    private var isAnalyzing = false
    /** Terminal state that transfers cleanup to an active recognition invocation. */
    private var isDisposed = false

    /**
     * Attempts recognition synchronously; busy, disposed or throttled calls return null. The caller
     * owns [frame] and must keep it open until this call returns.
     */
    fun analyze(frame: CameraFrame, cropRect: Rect?): Barcode? {
        synchronized(lifecycleLock) {
            if (isDisposed || isAnalyzing) return null
            isAnalyzing = true
        }
        return try {
            if (!frameAnalysisGate.acceptsFrame()) return null
            var result: Barcode? = null
            try {
                analyzeFrame(frame, cropRect).also { result = it }
            } finally {
                frameAnalysisGate.completeAnalysis(barcodeFound = result != null)
            }
        } finally {
            val shouldClose =
                synchronized(lifecycleLock) {
                    isAnalyzing = false
                    isDisposed
                }
            if (shouldClose) disposeAnalyzer()
        }
    }

    /** Updates the cooldown applied only after a successful recognition. */
    fun updatePeriod(periodMs: Int) {
        frameAnalysisGate.updateSuccessfulScanPeriod(periodMs)
    }

    /**
     * Rejects new analysis and closes idle resources on the calling thread. An active invocation
     * owns deferred cleanup; this call does not wait for it or invalidate its result.
     */
    fun dispose() {
        val shouldClose =
            synchronized(lifecycleLock) {
                if (isDisposed) return
                isDisposed = true
                !isAnalyzing
            }
        // Either this call owns cleanup, or the active analyze() will do it in its finally.
        if (shouldClose) disposeAnalyzer()
    }

    /**
     * Analyzes an accepted frame. Implementations must finish all access to borrowed frame data
     * before returning, including SDK work that continues after a thread is interrupted.
     */
    protected abstract fun analyzeFrame(frame: CameraFrame, cropRect: Rect?): Barcode?

    /** Releases owned resources once, on the disposing thread or the last analysis thread. */
    protected abstract fun disposeAnalyzer()
}
