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
        /** The period must be updated through the [updatePeriod] method */
        protected set

    /** Attempts to recognize a barcode value from the provided image. */
    abstract fun analyze(image: AnalysingImage): String?

    /**
     * Method for cleaning analyzer resources
     *
     * Is a template method that is called in [dispose]
     */
    protected abstract fun clearResources()

    /** Initializes analyzer timing and implementation-specific resources. */
    fun init(period: Int) {
        analyzePeriodMs = period
        onInit()
    }

    /** Implementation hook called after [analyzePeriodMs] is initialized. */
    protected open fun onInit() = Unit

    /**
     * Period updating can be accompanied by additional actions (creating new threads for example).
     * These actions should be performed in the method
     */
    fun updatePeriod(periodMs: Int) {
        analyzePeriodMs = periodMs
        onPeriodUpdated()
    }

    /** Implementation hook called after [analyzePeriodMs] is updated. */
    protected open fun onPeriodUpdated() = Unit

    /** Releases analyzer resources. */
    fun dispose() {
        clearResources()
    }
}
