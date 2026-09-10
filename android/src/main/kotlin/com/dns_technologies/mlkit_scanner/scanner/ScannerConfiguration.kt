package com.dns_technologies.mlkit_scanner.scanner

import com.dns_technologies.mlkit_scanner.PluginError
import com.dns_technologies.mlkit_scanner.scanner.models.RecognizeVisorCropRect
import com.dns_technologies.mlkit_scanner.utils.optionalBoolean
import com.dns_technologies.mlkit_scanner.utils.optionalMap
import com.dns_technologies.mlkit_scanner.utils.requireFiniteDouble
import com.dns_technologies.mlkit_scanner.utils.requireInt
import com.dns_technologies.mlkit_scanner.utils.requireMap

/** One validated capture payload. Never stored on a native View or in a configuration registry. */
internal data class ScannerConfiguration(
    val zoomRatio: Float = 1F,
    val torchEnabled: Boolean = false,
    val cropArea: RecognizeVisorCropRect? = null,
    val scanEnabled: Boolean = false,
    val scanDelay: Int = 0,
) {
    companion object {
        /** Parses the channel payload; the scan-delay range is validated by Dart. */
        fun from(arguments: Any?): ScannerConfiguration {
            val values = arguments.requireMap()
            val zoom = values.requireFiniteDouble("zoomRatio").toFloat()
            if (!zoom.isFinite() || zoom <= 0) throw PluginError.InvalidArguments
            return ScannerConfiguration(
                zoomRatio = zoom,
                torchEnabled = values.optionalBoolean("torchEnabled") ?: throw PluginError.InvalidArguments,
                cropArea = values.optionalMap("cropRect")?.let(RecognizeVisorCropRect::fromMap),
                scanEnabled = values.optionalBoolean("scanEnabled") ?: throw PluginError.InvalidArguments,
                scanDelay = values.requireInt("scanDelay"),
            )
        }
    }
}
