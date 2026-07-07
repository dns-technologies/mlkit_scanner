package com.dns_technologies.mlkit_scanner.scanner.components.analyzer

import com.dns_technologies.mlkit_scanner.scanner.components.analyzer.optimization.AnalyzeDelayTimer
import com.dns_technologies.mlkit_scanner.scanner.models.Barcode
import com.dns_technologies.mlkit_scanner.scanner.models.images.AnalysingImage

/**
 * Abstract class of a barcode image analyzer.
 *
 * The class provides common timing and concurrency behavior for analyzer implementations.
 */
abstract class ImageBarcodeAnalyzer {
    /** Minimum delay between image analysis attempts. */
    var analyzePeriodMs: Int = 0
        protected set

    private val analyzeDelayTimer = AnalyzeDelayTimer(analyzePeriodMs)

    /** Controls concurrent recognition; only one frame is analyzed at a time. */
    private var isAnalysisInProgress = false

    /** Attempts to recognize a barcode from the provided image. */
    fun analyze(image: AnalysingImage): Barcode? {
        if (!shouldAnalyzeCurrentFrame()) {
            return null
        }

        isAnalysisInProgress = true
        return try {
            analyzeImage(image)
        } finally {
            analyzeDelayTimer.restart()
            isAnalysisInProgress = false
        }
    }

    /** Initializes common analyzer timing. */
    fun init(period: Int) {
        analyzePeriodMs = period
        analyzeDelayTimer.updatePeriod(analyzePeriodMs)
    }

    /** Updates common analyzer timing. */
    fun updatePeriod(periodMs: Int) {
        analyzePeriodMs = periodMs
        analyzeDelayTimer.updatePeriod(analyzePeriodMs)
    }

    /** Releases analyzer resources. */
    fun dispose() {
        analyzeDelayTimer.stop()
        disposeAnalyzer()
    }

    /** Attempts to recognize a barcode from the provided image. */
    protected abstract fun analyzeImage(image: AnalysingImage): Barcode?

    /** Releases implementation-specific analyzer resources. */
    protected abstract fun disposeAnalyzer()

    /** Returns true when the current frame can be sent to a concrete analyzer. */
    private fun shouldAnalyzeCurrentFrame(): Boolean =
        !isAnalysisInProgress &&
            !analyzeDelayTimer.isRunning
}
