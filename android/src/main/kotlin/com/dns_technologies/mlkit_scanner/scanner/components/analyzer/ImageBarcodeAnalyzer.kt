package com.dns_technologies.mlkit_scanner.scanner.components.analyzer

import com.dns_technologies.mlkit_scanner.scanner.components.analyzer.optimization.FrameAnalysisGate
import com.dns_technologies.mlkit_scanner.scanner.components.camera.CameraFrame
import com.dns_technologies.mlkit_scanner.scanner.components.camera.Rect
import com.dns_technologies.mlkit_scanner.scanner.models.Barcode
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Base class for barcode analyzers independent of a concrete image-analysis library.
 *
 * Provides time-based frame throttling, exclusive frame processing and thread-safe resource
 * disposal to every implementation.
 *
 * @param currentTimeMs Monotonic clock used by the analysis throttle.
 */
abstract class ImageBarcodeAnalyzer protected constructor(
    currentTimeMs: () -> Long = android.os.SystemClock::elapsedRealtime,
) {
    private val frameAnalysisGate = FrameAnalysisGate(0, currentTimeMs)
    private val isAnalyzing = AtomicBoolean(false)
    private val isDisposed = AtomicBoolean(false)
    private val isClosed = AtomicBoolean(false)

    /** Attempts recognition for the first frame available after the active cooldown. */
    fun analyze(frame: CameraFrame, cropRect: Rect?): Barcode? {
        if (isDisposed.get() || !isAnalyzing.compareAndSet(false, true)) return null

        var analysisAccepted = false
        var analysisResult: Barcode? = null
        return try {
            if (isDisposed.get() || !frameAnalysisGate.acceptsFrame()) {
                null
            } else {
                analysisAccepted = true
                analyzeFrame(frame, cropRect).also { analysisResult = it }
            }
        } finally {
            try {
                if (analysisAccepted && !isDisposed.get()) {
                    frameAnalysisGate.completeAnalysis(analysisResult != null)
                }
            } finally {
                isAnalyzing.set(false)
                closeIfDisposedAndIdle()
            }
        }
    }

    /** Updates the cooldown applied only after a successful recognition. */
    fun updatePeriod(periodMs: Int) {
        frameAnalysisGate.updateSuccessfulScanPeriod(periodMs)
    }

    /** Marks the analyzer disposed and releases its resources once active analysis has finished. */
    fun dispose() {
        isDisposed.compareAndSet(false, true)
        closeIfDisposedAndIdle()
    }

    private fun closeIfDisposedAndIdle() {
        if (!isDisposed.get() || !isAnalyzing.compareAndSet(false, true)) return

        try {
            if (isClosed.compareAndSet(false, true)) {
                disposeAnalyzer()
            }
        } finally {
            isAnalyzing.set(false)
        }
    }

    /** Analyzes a frame and the requested source crop accepted by the common execution policy. */
    protected abstract fun analyzeFrame(frame: CameraFrame, cropRect: Rect?): Barcode?

    /** Releases resources owned by the concrete analyzer implementation. */
    protected abstract fun disposeAnalyzer()
}
