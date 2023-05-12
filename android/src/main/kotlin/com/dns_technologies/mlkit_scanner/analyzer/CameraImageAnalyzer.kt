package com.dns_technologies.mlkit_scanner.analyzer

import com.dns_technologies.mlkit_scanner.models.RecognitionType
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage

typealias OnSuccessListener = (result: Barcode) -> Unit
typealias ImageAnalyzePreparer = (image: AnalysingImage) -> InputImage?

/**
 * Abstract class of an image analyzer
 *
 * The class provides a common interface for working with the analyzer
 */
abstract class CameraImageAnalyzer(val type: RecognitionType) {

    /** An image will be analyzed once during this period */
    var analyzePeriodMs: Int = 0
        /** The period must be updated through the [updatePeriod] method */
        protected set

    /** The flag indicates whether the [dispose] method has been called for the analyzer */
    var isDisposed = false
        private set

    protected var isPause = false
        private set

    abstract fun analyze(image: AnalysingImage)

    /**
     * Method for cleaning analyzer resources
     *
     * Is a template method that is called in [dispose]
     */
    protected abstract fun clearResources()

    /**
     * Initialisation of the analyzer
     *
     * [period] - for the initialisation of the [analyzePeriodMs]
     * [successAnalyzeListener] - the method is called upon successful image analysis,
     * when required by user information is found in it (barcode for example)
     * [imagePreparer] - preparing an image transmitted by a camera for analysis
     */
    abstract fun init(
        period: Int,
        successAnalyzeListener: OnSuccessListener,
        imagePreparer: ImageAnalyzePreparer? = null
    )

    /**
     * Period updating can be accompanied by additional actions (creating new threads for example).
     * These actions should be performed in the method
     */
    abstract fun updatePeriod(periodMs: Int)

    fun pauseScan() {
        isPause = true
    }

    fun resumeScan(period: Int) {
        updatePeriod(period)
        isPause = false
    }

    fun dispose() {
        clearResources()
        isDisposed = true
    }
}