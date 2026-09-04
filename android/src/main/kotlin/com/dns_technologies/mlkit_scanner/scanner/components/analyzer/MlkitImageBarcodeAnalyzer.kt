package com.dns_technologies.mlkit_scanner.scanner.components.analyzer

import android.util.Log
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
 * Analyzes at most one barcode per recognition iteration.
 */
class MlkitImageBarcodeAnalyzer internal constructor(
    private val barcodeScanner: BarcodeScanner,
    currentTimeMs: () -> Long,
    private val logError: (String) -> Unit,
    private val awaitBarcodes: (Task<List<MlkitBarcode>>) -> List<MlkitBarcode> = Tasks::await,
    private val fromByteArray: (ByteArray, Int, Int, Int, Int) -> InputImage =
        InputImage::fromByteArray,
) : ImageBarcodeAnalyzer(currentTimeMs) {
    /** Creates the production analyzer and logs recognition failures under [logTag]. */
    constructor(logTag: String) : this(
        barcodeScanner = BarcodeScanning.getClient(),
        currentTimeMs = android.os.SystemClock::elapsedRealtime,
        logError = { message -> Log.e(logTag, message) },
    )

    /** Lazily creates one cropped recognition image for an accepted frame. */
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

    /** Closes the underlying barcode recognizer. */
    override fun disposeAnalyzer() {
        barcodeScanner.close()
    }

    /** Runs barcode recognition for the provided scanner image. */
    private fun analyzeImage(image: InputImage): Barcode? {
        return try {
            val barcode = awaitBarcodes(barcodeScanner.process(image))
                .firstNotNullOfOrNull { it.toScannerBarcode() }
                ?: return null

            barcode
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } catch (error: Exception) {
            logError(error.message ?: error.javaClass.simpleName)
            null
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
        const val UNKNOWN_FORMAT_CODE = 0
    }
}
