package com.dns_technologies.mlkit_scanner.scanner.components.analyzer

import com.dns_technologies.mlkit_scanner.scanner.components.analyzer.optimization.AnalysisAttemptWindow
import com.dns_technologies.mlkit_scanner.scanner.components.camera.CameraFrame
import com.dns_technologies.mlkit_scanner.scanner.components.camera.Rect
import com.dns_technologies.mlkit_scanner.scanner.models.Barcode
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Base class for barcode analyzers independent of a concrete image-analysis library.
 *
 * Provides bounded three-attempt windows, exclusive frame processing and thread-safe resource
 * disposal to every implementation.
 *
 * @param currentTimeMs Monotonic clock used by the analysis throttle.
 */
abstract class ImageBarcodeAnalyzer protected constructor(
    currentTimeMs: () -> Long = android.os.SystemClock::elapsedRealtime,
) {
    private val attemptWindow = AnalysisAttemptWindow(0, currentTimeMs)
    private val isAnalyzing = AtomicBoolean(false)
    private val isDisposed = AtomicBoolean(false)
    private val isClosed = AtomicBoolean(false)

    /** Attempts recognition when the active three-attempt window can accept another frame. */
    fun analyze(frame: CameraFrame, cropRect: Rect?): Barcode? {
        if (isDisposed.get() || !isAnalyzing.compareAndSet(false, true)) return null

        var analysisAccepted = false
        var analysisResult: Barcode? = null
        return try {
            if (isDisposed.get() || !attemptWindow.acceptsAttempt) {
                null
            } else {
                analysisAccepted = true
                analyzeFrame(frame, cropRect).also { analysisResult = it }
            }
        } finally {
            try {
                if (analysisAccepted && !isDisposed.get()) {
                    attemptWindow.completeAttempt(analysisResult != null)
                }
            } finally {
                isAnalyzing.set(false)
                closeIfDisposedAndIdle()
            }
        }
    }

    /** Updates the cooldown between bounded analysis windows. */
    fun updatePeriod(periodMs: Int) {
        attemptWindow.updatePeriod(periodMs)
    }

    /** Marks the analyzer disposed and releases its resources once active analysis has finished. */
    fun dispose() {
        if (isDisposed.compareAndSet(false, true)) {
            attemptWindow.reset()
        }

        closeIfDisposedAndIdle()
    }

    private fun closeIfDisposedAndIdle() {
        if (!isDisposed.get() || !isAnalyzing.compareAndSet(false, true)) return

        try {
            attemptWindow.reset()
            if (isClosed.compareAndSet(false, true)) {
                disposeAnalyzer()
            }
        } finally {
            isAnalyzing.set(false)
        }
    }

    /** Analyzes a frame accepted by the common execution policy. */
    protected abstract fun analyzeFrame(frame: CameraFrame, cropRect: Rect?): Barcode?

    /** Releases resources owned by the concrete analyzer implementation. */
    protected abstract fun disposeAnalyzer()
}
