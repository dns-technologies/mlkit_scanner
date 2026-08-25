package com.dns_technologies.mlkit_scanner.scanner.components.analyzer

import com.dns_technologies.mlkit_scanner.scanner.components.analyzer.optimization.AnalyzeDelayTimer
import com.dns_technologies.mlkit_scanner.scanner.components.camera.CameraFrame
import com.dns_technologies.mlkit_scanner.scanner.components.camera.Rect
import com.dns_technologies.mlkit_scanner.scanner.models.Barcode
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Base class for barcode analyzers independent of a concrete image-analysis library.
 *
 * Provides throttling, exclusive frame processing and thread-safe resource disposal to every
 * implementation.
 */
abstract class ImageBarcodeAnalyzer protected constructor(
    currentTimeMs: () -> Long = android.os.SystemClock::elapsedRealtime,
) {
    private val analyzeDelayTimer = AnalyzeDelayTimer(0, currentTimeMs)
    private val isAnalyzing = AtomicBoolean(false)
    private val isDisposed = AtomicBoolean(false)
    private val isClosed = AtomicBoolean(false)

    /** Attempts to recognize a barcode when the analyzer is ready to accept another frame. */
    fun analyze(frame: CameraFrame, cropRect: Rect?): Barcode? {
        if (isDisposed.get() || !isAnalyzing.compareAndSet(false, true)) return null

        var analysisAccepted = false
        return try {
            if (isDisposed.get() || analyzeDelayTimer.isRunning) {
                null
            } else {
                analysisAccepted = true
                analyzeFrame(frame, cropRect)
            }
        } finally {
            try {
                if (analysisAccepted && !isDisposed.get()) {
                    analyzeDelayTimer.restart()
                }
            } finally {
                isAnalyzing.set(false)
                closeIfDisposedAndIdle()
            }
        }
    }

    /** Updates the minimum delay between accepted analysis attempts. */
    fun updatePeriod(periodMs: Int) {
        analyzeDelayTimer.updatePeriod(periodMs)
    }

    /** Marks the analyzer disposed and releases its resources once active analysis has finished. */
    fun dispose() {
        if (isDisposed.compareAndSet(false, true)) {
            analyzeDelayTimer.stop()
        }

        closeIfDisposedAndIdle()
    }

    private fun closeIfDisposedAndIdle() {
        if (!isDisposed.get() || !isAnalyzing.compareAndSet(false, true)) return

        try {
            analyzeDelayTimer.stop()
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
