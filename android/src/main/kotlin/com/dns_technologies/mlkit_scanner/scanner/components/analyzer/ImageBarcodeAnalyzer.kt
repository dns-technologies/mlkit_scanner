package com.dns_technologies.mlkit_scanner.scanner.components.analyzer

import com.dns_technologies.mlkit_scanner.scanner.models.images.AnalysingImage

/**
 * Abstract class of an image analyzer
 *
 * The class provides a common interface for working with the analyzer
 */
abstract class ImageBarcodeAnalyzer {

    /** An image will be analyzed once during this period */
    var analyzePeriodMs: Int = 0
        protected set

    /** Attempts to recognize a barcode value from the provided image. */
    abstract fun analyze(image: AnalysingImage): String?

    /** Initializes analyzer timing and lets implementations extend initialization behavior. */
    open fun init(period: Int) {
        analyzePeriodMs = period
    }

    /** Updates analyzer period and lets implementations extend period-change behavior. */
    open fun updatePeriod(periodMs: Int) {
        analyzePeriodMs = periodMs
    }

    /** Releases analyzer resources. */
    abstract fun dispose()
}
