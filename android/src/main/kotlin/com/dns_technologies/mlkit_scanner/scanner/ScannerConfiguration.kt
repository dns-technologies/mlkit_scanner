package com.dns_technologies.mlkit_scanner.scanner

import com.dns_technologies.mlkit_scanner.scanner.models.RecognizeVisorCropRect
import com.dns_technologies.mlkit_scanner.utils.optionalMap
import com.dns_technologies.mlkit_scanner.utils.requireBoolean
import com.dns_technologies.mlkit_scanner.utils.requireFiniteFloat
import com.dns_technologies.mlkit_scanner.utils.requireMap

/** Camera settings to apply when activating a capture. */
internal data class ScannerConfiguration(
    /** Absolute zoom ratio to apply when capture starts. */
    val zoomRatio: Float = 1F,
    /** Desired torch state for this capture snapshot. */
    val torchEnabled: Boolean = false,
    /** Optional recognition region expressed relative to the preview viewport. */
    val cropArea: RecognizeVisorCropRect? = null,
) {
    companion object {
        /** Decodes camera settings from a capture argument map. */
        fun from(arguments: Any?): ScannerConfiguration {
            val values = arguments.requireMap()
            return ScannerConfiguration(
                zoomRatio = values.requireFiniteFloat("zoomRatio"),
                torchEnabled = values.requireBoolean("torchEnabled"),
                cropArea = values.optionalMap("cropRect")?.let(RecognizeVisorCropRect::fromMap),
            )
        }
    }
}
