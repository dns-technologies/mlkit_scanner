package com.dns_technologies.mlkit_scanner.scanner.components.analyzer

import android.util.Log
import com.dns_technologies.mlkit_scanner.BuildConfig
import com.dns_technologies.mlkit_scanner.scanner.models.images.AnalysingImage
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage

/**
 * [ImageBarcodeAnalyzer] implementation used for barcode analyzing.
 *
 * Analyzes one barcode per recognition iteration using ML Kit.
 */
class MlkitImageBarcodeAnalyzer(
    private val logTag: String,
) : ImageBarcodeAnalyzer() {
    private val barcodeScanner = BarcodeScanning.getClient()

    override fun disposeAnalyzer() {
        barcodeScanner.close()
    }

    /** Runs ML Kit barcode recognition when analyzer throttling allows it. */
    override fun analyzeImage(image: AnalysingImage): String? {
        return try {
            val barcode = Tasks.await(barcodeScanner.process(image.toMlKitInputImage()))
                .firstOrNull { it.rawValue != null }
                ?: return null

            val rawValue = barcode.rawValue ?: return null
            if (BuildConfig.DEBUG) {
                Log.d(logTag, rawValue)
            }
            rawValue
        } catch (e: Exception) {
            if (e.message != null) {
                Log.e(logTag, e.message!!)
            }
            null
        }
    }

    /** Converts a scanner image into the ML Kit input image type. */
    private fun AnalysingImage.toMlKitInputImage() = InputImage.fromByteArray(
        data,
        width,
        height,
        rotationDegree,
        format,
    )
}
