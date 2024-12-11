package com.dns_technologies.mlkit_scanner.analyzer

import android.util.Log
import com.dns_technologies.mlkit_scanner.models.RecognitionType
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

const val TAG = "ML_BARCODE_SCANNER"
// todo add logic for determining the minimum possible analysis period depending on the fps of the camera
// corresponds to the minimum delay with which frames can get into analyze at 60 fps of the camera
const val MIN_ANALYZE_DELAY_MS = 16
const val SKIP_FRAME_COUNT = 7

/**
 * [CameraImageAnalyzer] realisation using for barcode analyzing
 *
 * Analyzes one barcode per recognition iteration. To reduce power consumption,
 * skips several frames per recognition iteration in accordance with the [SKIP_FRAME_COUNT].
 * The MlKit algorithm is used as recognition.
 */
class MlSingleBarcodeAnalyzer(type: RecognitionType) : CameraImageAnalyzer(type) {
    private val barcodeScanner = BarcodeScanning.getClient()
    /// an image analysis capability control flag. Set to true one time per recognition iteration
    private var isAnalysisInProgress = false
    /// the flag indicates whether the delay timer is running between successful scans.
    /// the delay is determined by analyzePeriodMsF
    private var isDelayedTimerStarted = false
    private var imagePreparer: ImageAnalyzePreparer? = null
    private var analyzePermissionExecutor: ScheduledExecutorService? = null
    private var skippingFrameCount = 0
    private lateinit var onSuccessListener: OnSuccessListener

    override fun analyze(image: AnalysingImage) {
        if (canAnalyze()) {
            isAnalysisInProgress = true
            val inputImage = imagePreparer?.invoke(image)
            if (inputImage != null) {
                tryAnalyzeInputImage(inputImage)
            } else {
                isAnalysisInProgress = false
            }
        } else {
            increaseSkippingFrameCount()
        }
    }

    /** Initialization of analyzer parameters and an analyze permission control thread */
    override fun init(
        period: Int,
        successAnalyzeListener: OnSuccessListener,
        imagePreparer: ImageAnalyzePreparer?
    ) {
        analyzePeriodMs = period
        onSuccessListener = successAnalyzeListener
        this.imagePreparer = imagePreparer
        startAnalyzeDelayTimer()
    }

    /** Recreating the scan resolution control thread with a new scan period */
    override fun updatePeriod(periodMs: Int) {
        analyzePermissionExecutor?.shutdown()
        analyzePeriodMs = periodMs
        startAnalyzeDelayTimer()
    }

    /** Closing all threads and cleaning up MLKit entities */
    override fun clearResources() {
        barcodeScanner.close()
        analyzePermissionExecutor?.shutdown()
    }

    private fun canAnalyze() = skippingFrameCount % SKIP_FRAME_COUNT == 0
            && !isAnalysisInProgress
            && !isPause
            && !isDelayedTimerStarted

    private fun tryAnalyzeInputImage(image: InputImage) {
        barcodeScanner.process(image)
            .addOnSuccessListener(this::onSuccessScan)
            .addOnCompleteListener {
                increaseSkippingFrameCount()
                isAnalysisInProgress = false
            }
            .addOnFailureListener {
                if (it.message != null) {
                    Log.e(TAG, it.message!!)
                }
                isAnalysisInProgress = false
            }
    }

    private fun onSuccessScan(barcodeList: List<Barcode>) {
        if (barcodeList.isNotEmpty()) {
            val barcode = barcodeList.first()
            if (barcode.rawValue != null) {
                Log.d(TAG, barcode.rawValue.toString())
                skippingFrameCount = 0
                startAnalyzeDelayTimer()
                onSuccessListener.invoke(barcode)
            }
        }
    }

    private fun increaseSkippingFrameCount() {
        skippingFrameCount = ++skippingFrameCount % SKIP_FRAME_COUNT
    }

    private fun startAnalyzeDelayTimer() {
        if (shouldAcceptPeriod()) {
            isDelayedTimerStarted = true // provides a delay before the first scan
            analyzePermissionExecutor?.shutdownNow()
            analyzePermissionExecutor = Executors.newSingleThreadScheduledExecutor()
            analyzePermissionExecutor?.schedule({
                isDelayedTimerStarted = false
            }, analyzePeriodMs.toLong(), TimeUnit.MILLISECONDS)
        }
    }

    private fun shouldAcceptPeriod() = analyzePeriodMs > MIN_ANALYZE_DELAY_MS * SKIP_FRAME_COUNT
}