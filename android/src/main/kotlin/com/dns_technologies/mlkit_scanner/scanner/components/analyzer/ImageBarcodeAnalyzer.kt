package com.dns_technologies.mlkit_scanner.scanner.components.analyzer

import com.dns_technologies.mlkit_scanner.scanner.components.analyzer.optimization.AnalyzeDelayTimer
import com.dns_technologies.mlkit_scanner.scanner.components.camera.CameraFrame
import com.dns_technologies.mlkit_scanner.scanner.components.camera.Rect
import com.dns_technologies.mlkit_scanner.scanner.models.Barcode
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

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
    private val stateLock = ReentrantLock()
    private var isDisposed = false

    /** Attempts to recognize a barcode when the analyzer is ready to accept another frame. */
    fun analyze(frame: CameraFrame, cropRect: Rect?): Barcode? {
        if (!stateLock.tryLock()) return null

        return try {
            if (isDisposed || analyzeDelayTimer.isRunning) {
                null
            } else {
                try {
                    analyzeFrame(frame, cropRect)
                } finally {
                    analyzeDelayTimer.restart()
                }
            }
        } finally {
            stateLock.unlock()
        }
    }

    /** Updates the minimum delay between accepted analysis attempts. */
    fun updatePeriod(periodMs: Int) {
        stateLock.withLock {
            analyzeDelayTimer.updatePeriod(periodMs)
        }
    }

    /** Waits for active analysis and releases implementation resources exactly once. */
    fun dispose() {
        stateLock.withLock {
            if (isDisposed) return

            isDisposed = true
            analyzeDelayTimer.stop()
            disposeAnalyzer()
        }
    }

    /** Analyzes a frame accepted by the common execution policy. */
    protected abstract fun analyzeFrame(frame: CameraFrame, cropRect: Rect?): Barcode?

    /** Releases resources owned by the concrete analyzer implementation. */
    protected abstract fun disposeAnalyzer()
}
