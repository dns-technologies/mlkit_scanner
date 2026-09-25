package com.dns_technologies.mlkit_scanner.scanner.components.analyzer.mlkit

import android.os.Looper
import android.util.Log
import androidx.annotation.WorkerThread
import com.dns_technologies.mlkit_scanner.PluginConstants
import com.dns_technologies.mlkit_scanner.scanner.components.analyzer.ImageBarcodeAnalyzer
import com.dns_technologies.mlkit_scanner.scanner.components.camera.CameraFrame
import com.dns_technologies.mlkit_scanner.scanner.components.camera.Rect
import com.dns_technologies.mlkit_scanner.scanner.models.Barcode
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode as MlkitBarcode
import com.google.mlkit.vision.common.InputImage

/**
 * Recognizes the first barcode with a raw value using ML Kit and a borrowed NV21 buffer. The worker
 * stays inside the frame's buffer scope until the asynchronous task has finished.
 */
class MlkitImageBarcodeAnalyzer
internal constructor(
    /** Owned ML Kit recognizer closed after all borrowed-byte work finishes. */
    private val barcodeScanner: BarcodeScanner,
    currentTimeMs: () -> Long,
    /** Reports recognition failures without exposing backend exceptions to callers. */
    private val logError: (String) -> Unit,
    /** Constructs an ML Kit image from the currently borrowed NV21 bytes. */
    private val fromByteArray: (ByteArray, Int, Int, Int, Int) -> InputImage =
        InputImage::fromByteArray,
) : ImageBarcodeAnalyzer(currentTimeMs) {
    /** Creates the production analyzer and logs recognition failures under the plugin tag. */
    constructor() :
        this(
            barcodeScanner = BarcodeScanning.getClient(),
            currentTimeMs = android.os.SystemClock::elapsedRealtime,
            logError = { message -> Log.e(PluginConstants.LOG_TAG, message) },
        )

    /** Lazily creates one cropped recognition image for an accepted frame. */
    @WorkerThread
    override fun analyzeFrame(frame: CameraFrame, cropRect: Rect?): Barcode? {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "ML Kit analysis requires a worker thread"
        }
        if (Thread.currentThread().isInterrupted) return null
        return frame.useNv21(cropRect) { bytes, width, height, rotation ->
            analyzeImage(
                fromByteArray(bytes, width, height, rotation, InputImage.IMAGE_FORMAT_NV21)
            )
        }
    }

    /** Closes the underlying barcode recognizer. */
    override fun disposeAnalyzer() {
        barcodeScanner.close()
    }

    /** Runs barcode recognition for the provided scanner image. */
    private fun analyzeImage(image: InputImage): Barcode? {
        var interrupted = false
        try {
            val task = barcodeScanner.process(image)
            while (true) {
                try {
                    val barcodes = Tasks.await(task)
                    if (interrupted || Thread.currentThread().isInterrupted) return null
                    return barcodes.firstNotNullOfOrNull { it.toScannerBarcode() }
                } catch (_: InterruptedException) {
                    // Interrupting await does not stop ML Kit from reading the borrowed bytes.
                    // Finish this same task before releasing its buffer or closing the scanner.
                    interrupted = true
                }
            }
        } catch (error: Exception) {
            logError(error.message ?: error.javaClass.simpleName)
            return null
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    /** Maps the recognition result at the adapter boundary without leaking backend types. */
    private fun MlkitBarcode.toScannerBarcode(): Barcode? {
        val rawValue = rawValue ?: return null
        return Barcode(
            rawValue = rawValue,
            displayValue = displayValue,
            format = if (format == MlkitBarcode.FORMAT_UNKNOWN) UNKNOWN_FORMAT_CODE else format,
            valueType = valueType,
        )
    }

    private companion object {
        // Normalize the backend's unknown-format marker to the cross-platform contract value.
        /** Cross-platform code used when ML Kit cannot identify a barcode format. */
        const val UNKNOWN_FORMAT_CODE = 0
    }
}
