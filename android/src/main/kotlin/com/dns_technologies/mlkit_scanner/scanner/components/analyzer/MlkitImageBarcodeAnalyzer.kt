package com.dns_technologies.mlkit_scanner.scanner.components.analyzer

import android.util.Log
import com.dns_technologies.mlkit_scanner.BuildConfig
import com.dns_technologies.mlkit_scanner.scanner.components.camera.CameraFrame
import com.dns_technologies.mlkit_scanner.scanner.components.camera.Rect
import com.dns_technologies.mlkit_scanner.scanner.models.Barcode
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.common.Barcode as MlkitBarcode
import com.google.mlkit.vision.common.InputImage

/**
 * [ImageBarcodeAnalyzer] implementation used for barcode analyzing.
 *
 * Analyzes one barcode per recognition iteration using ML Kit.
 */
class MlkitImageBarcodeAnalyzer internal constructor(
    private val barcodeScanner: BarcodeScanner,
    currentTimeMs: () -> Long,
    private val logError: (String) -> Unit,
    private val logDebug: (String) -> Unit,
    private val awaitBarcodes: (Task<List<MlkitBarcode>>) -> List<MlkitBarcode> = Tasks::await,
    private val fromByteArray: (ByteArray, Int, Int, Int, Int) -> InputImage =
        InputImage::fromByteArray,
) : ImageBarcodeAnalyzer(currentTimeMs) {
    constructor(logTag: String) : this(
        barcodeScanner = BarcodeScanning.getClient(),
        currentTimeMs = android.os.SystemClock::elapsedRealtime,
        logError = { message -> Log.e(logTag, message) },
        logDebug = { message -> Log.d(logTag, message) },
    )

    /** Lazily creates an ML Kit NV21 image for a frame accepted by the common analyzer policy. */
    override fun analyzeFrame(frame: CameraFrame, cropRect: Rect?): Barcode? {
        return frame.useNv21(
            cropRect = cropRect,
            block = { bytes, width, height, rotationDegree ->
                analyzeImage(
                    fromByteArray(
                        bytes,
                        width,
                        height,
                        rotationDegree,
                        InputImage.IMAGE_FORMAT_NV21,
                    ),
                )
            },
        )
    }

    /** Closes the underlying ML Kit barcode scanner. */
    override fun disposeAnalyzer() {
        barcodeScanner.close()
    }

    /** Runs ML Kit barcode recognition for the provided scanner image. */
    private fun analyzeImage(image: InputImage): Barcode? {
        return try {
            val barcode = awaitBarcodes(barcodeScanner.process(image))
                .firstOrNull { it.rawValue != null }
                ?: return null

            val rawValue = barcode.rawValue ?: return null
            if (BuildConfig.DEBUG) {
                logDebug(rawValue)
            }
            Barcode(rawValue)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } catch (error: Exception) {
            error.message?.let(logError)
            null
        }
    }
}
