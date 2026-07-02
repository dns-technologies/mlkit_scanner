package com.dns_technologies.mlkit_scanner.scanner.components.analyzer

import android.util.Log
import com.dns_technologies.mlkit_scanner.scanner.models.images.AnalysingImage
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * [ImageBarcodeAnalyzer] implementation used for barcode analyzing.
 *
 * Analyzes one barcode per recognition iteration using ML Kit.
 */
class MlkitImageBarcodeAnalyzer : ImageBarcodeAnalyzer() {
    private val barcodeScanner = BarcodeScanning.getClient()

    /** Controls concurrent recognition; only one frame is analyzed at a time. */
    private var isAnalysisInProgress = false

    /** Indicates whether the delay timer is running between recognition attempts. */
    private var isDelayTimerRunning = false

    private var analyzeDelayExecutor: ScheduledExecutorService? = null
    private var skippingFrameCount = 0

    override fun analyze(image: AnalysingImage): String? {
        return tryAnalyzeInputImage(image.toMlKitInputImage())
    }

    override fun onInit() {
        startAnalyzeDelayTimer()
    }

    override fun onPeriodUpdated() {
        stopAnalyzeDelayTimer()
        startAnalyzeDelayTimer()
    }

    override fun clearResources() {
        barcodeScanner.close()
        stopAnalyzeDelayTimer()
    }

    /** Runs ML Kit barcode recognition when analyzer throttling allows it. */
    private fun tryAnalyzeInputImage(image: InputImage): String? {
        if (!shouldAnalyzeCurrentFrame()) {
            increaseSkippingFrameCount()
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
            skippingFrameCount = 0
            startAnalyzeDelayTimer()
            rawValue
        } catch (e: Exception) {
            if (e.message != null) {
                Log.e(TAG, e.message!!)
            }
            null
        } finally {
            if (shouldSkipNextFrame) {
                increaseSkippingFrameCount()
            }
            isAnalysisInProgress = false
        }
    }

    /** Returns true when the current frame can be sent to ML Kit. */
    private fun shouldAnalyzeCurrentFrame(): Boolean =
        skippingFrameCount % SKIP_FRAME_COUNT == 0 &&
                !isAnalysisInProgress &&
                !isDelayTimerRunning

    /** Advances the skipped-frame counter within its configured range. */
    private fun increaseSkippingFrameCount() {
        skippingFrameCount = ++skippingFrameCount % SKIP_FRAME_COUNT
    }

    /** Starts the timer that throttles consecutive analysis attempts. */
    private fun startAnalyzeDelayTimer() {
        stopAnalyzeDelayTimer()
        if (shouldAcceptPeriod()) {
            isDelayTimerRunning = true
            analyzeDelayExecutor = Executors.newSingleThreadScheduledExecutor()
            analyzeDelayExecutor?.schedule({
                isDelayTimerRunning = false
            }, analyzePeriodMs.toLong(), TimeUnit.MILLISECONDS)
        }
    }

    /** Stops the active analysis delay timer, if one exists. */
    private fun stopAnalyzeDelayTimer() {
        analyzeDelayExecutor?.shutdownNow()
        analyzeDelayExecutor = null
        isDelayTimerRunning = false
    }

    /** Returns true when the configured period is large enough to throttle frames. */
    private fun shouldAcceptPeriod() = analyzePeriodMs > MIN_ANALYZE_DELAY_MS * SKIP_FRAME_COUNT

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
