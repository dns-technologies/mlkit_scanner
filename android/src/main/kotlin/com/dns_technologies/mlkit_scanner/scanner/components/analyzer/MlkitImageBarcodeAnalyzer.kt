package com.dns_technologies.mlkit_scanner.scanner.components.analyzer

import android.util.Log
import com.dns_technologies.mlkit_scanner.scanner.models.images.AnalysingImage
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage

/**
 * [ImageBarcodeAnalyzer] implementation used for barcode analyzing.
 *
 * Analyzes one barcode per recognition iteration using ML Kit.
 */
class MlkitImageBarcodeAnalyzer : ImageBarcodeAnalyzer() {
    private val barcodeScanner = BarcodeScanning.getClient()
    private val analyzeDelayTimer = AnalyzeDelayTimer(MIN_ANALYZE_DELAY_MS * SKIP_FRAME_COUNT)
    private val skippingFrameCounter = SkippingFrameCounter(SKIP_FRAME_COUNT)

    /** Controls concurrent recognition; only one frame is analyzed at a time. */
    private var isAnalysisInProgress = false

    override fun analyze(image: AnalysingImage): String? {
        return tryAnalyzeInputImage(image.toMlKitInputImage())
    }

    override fun init(period: Int) {
        super.init(period)
        analyzeDelayTimer.restart(analyzePeriodMs)
    }

    override fun updatePeriod(periodMs: Int) {
        super.updatePeriod(periodMs)
        analyzeDelayTimer.restart(analyzePeriodMs)
    }

    override fun dispose() {
        barcodeScanner.close()
        analyzeDelayTimer.stop()
    }

    /** Runs ML Kit barcode recognition when analyzer throttling allows it. */
    private fun tryAnalyzeInputImage(image: InputImage): String? {
        if (!shouldAnalyzeCurrentFrame()) {
            skippingFrameCounter.advance()
            return null
        }

        isAnalysisInProgress = true
        var shouldSkipNextFrame = true
        return try {
            val barcode = Tasks.await(barcodeScanner.process(image))
                .firstOrNull { it.rawValue != null }
                ?: return null

            val rawValue = barcode.rawValue ?: return null
            Log.d(TAG, rawValue)
            shouldSkipNextFrame = false
            skippingFrameCounter.reset()
            analyzeDelayTimer.restart(analyzePeriodMs)
            rawValue
        } catch (e: Exception) {
            if (e.message != null) {
                Log.e(TAG, e.message!!)
            }
            null
        } finally {
            if (shouldSkipNextFrame) {
                skippingFrameCounter.advance()
            }
            isAnalysisInProgress = false
        }
    }

    /** Returns true when the current frame can be sent to ML Kit. */
    private fun shouldAnalyzeCurrentFrame(): Boolean =
        skippingFrameCounter.shouldAnalyzeCurrentFrame() &&
                !isAnalysisInProgress &&
                !analyzeDelayTimer.isRunning

    /** Converts a scanner image into the ML Kit input image type. */
    private fun AnalysingImage.toMlKitInputImage() = InputImage.fromByteArray(
        data,
        width,
        height,
        rotationDegree,
        format,
    )

    private companion object {
        const val TAG = "ML_BARCODE_SCANNER"

        // Minimum delay between frames at 60 FPS. Used as a lower bound for scan throttling.
        const val MIN_ANALYZE_DELAY_MS = 16

        const val SKIP_FRAME_COUNT = 7
    }
}
