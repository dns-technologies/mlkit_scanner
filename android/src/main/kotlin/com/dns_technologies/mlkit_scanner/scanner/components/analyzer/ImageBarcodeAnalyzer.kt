package com.dns_technologies.mlkit_scanner.scanner.components.analyzer

import com.dns_technologies.mlkit_scanner.scanner.components.analyzer.optimization.AnalyzeDelayTimer
import com.dns_technologies.mlkit_scanner.scanner.models.Barcode
import com.dns_technologies.mlkit_scanner.scanner.models.images.AnalysingImage
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Abstract class of a barcode image analyzer.
 *
 * The class provides common timing and concurrency behavior for analyzer implementations.
 */
abstract class ImageBarcodeAnalyzer protected constructor(
    currentTimeMs: () -> Long = android.os.SystemClock::elapsedRealtime,
) {
    private val analyzeDelayTimer = AnalyzeDelayTimer(0, currentTimeMs)
    private val stateLock = ReentrantLock()
    private var isDisposed = false

    /** Lazily creates and analyzes an image when the current frame is accepted. */
    fun analyze(createImage: () -> AnalysingImage): Barcode? {
        if (!stateLock.tryLock()) {
            return null
        }

        return try {
            if (isDisposed || analyzeDelayTimer.isRunning) {
                null
            } else {
                try {
                    analyzeImage(createImage())
                } finally {
                    analyzeDelayTimer.restart()
                }
            }
        } finally {
            stateLock.unlock()
        }
    }

    /** Initializes common analyzer timing. */
    fun init(period: Int) {
        updatePeriod(period)
    }

    /** Updates common analyzer timing. */
    fun updatePeriod(periodMs: Int) {
        stateLock.withLock {
            analyzeDelayTimer.updatePeriod(periodMs)
        }
    }

    /** Releases analyzer resources. */
    fun dispose() {
        stateLock.withLock {
            if (isDisposed) return

            isDisposed = true
            analyzeDelayTimer.stop()
            disposeAnalyzer()
        }
    }

    /** Attempts to recognize a barcode from the provided image. */
    protected abstract fun analyzeImage(image: AnalysingImage): Barcode?

    /** Releases implementation-specific analyzer resources. */
    protected abstract fun disposeAnalyzer()
}
