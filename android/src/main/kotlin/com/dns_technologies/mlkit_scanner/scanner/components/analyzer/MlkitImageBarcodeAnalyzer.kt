package com.dns_technologies.mlkit_scanner.scanner.components.analyzer

import android.util.Log
import com.dns_technologies.mlkit_scanner.BuildConfig
import com.dns_technologies.mlkit_scanner.scanner.models.Barcode
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.common.InputImage

/**
 * [ImageBarcodeAnalyzer] implementation used for barcode analyzing.
 *
 * Analyzes one barcode per recognition iteration using ML Kit.
 */
class MlkitImageBarcodeAnalyzer internal constructor(
    private val logTag: String,
    private val barcodeScanner: BarcodeScanner,
    currentTimeMs: () -> Long,
    private val logError: (String) -> Unit,
) : ImageBarcodeAnalyzer(currentTimeMs) {
    constructor(logTag: String) : this(
        logTag = logTag,
        barcodeScanner = BarcodeScanning.getClient(),
        currentTimeMs = android.os.SystemClock::elapsedRealtime,
        logError = { message -> Log.e(logTag, message) },
    )

    /** Closes the underlying ML Kit barcode scanner. */
    override fun disposeAnalyzer() {
        barcodeScanner.close()
    }

    /** Runs ML Kit barcode recognition for the provided scanner image. */
    override fun analyzeImage(image: InputImage): Barcode? {
        return try {
            val barcode = Tasks.await(barcodeScanner.process(image))
                .firstOrNull { it.rawValue != null }
                ?: return null

            val rawValue = barcode.rawValue ?: return null
            if (BuildConfig.DEBUG) {
                Log.d(logTag, rawValue)
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
