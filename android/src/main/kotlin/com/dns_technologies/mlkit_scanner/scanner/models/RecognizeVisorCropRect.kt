package com.dns_technologies.mlkit_scanner.scanner.models

import com.dns_technologies.mlkit_scanner.PluginError
import com.dns_technologies.mlkit_scanner.utils.optionalFiniteDouble

/**
 * Defines the recognized area inside the scanner camera preview.
 *
 * @property scaleWidth Recognition area width as a fraction of the preview width.
 * @property scaleHeight Recognition area height as a fraction of the preview height.
 * @property centerOffsetX Horizontal center offset relative to the preview center.
 * @property centerOffsetY Vertical center offset relative to the preview center.
 */
data class RecognizeVisorCropRect(
    val scaleWidth: Double = 1.0,
    val scaleHeight: Double = 1.0,
    val centerOffsetX: Double = 0.0,
    val centerOffsetY: Double = 0.0,
) {
    companion object {
        /** Creates a crop rectangle from StandardMessageCodec map values. */
        internal fun fromMap(arguments: Map<*, *>): RecognizeVisorCropRect {
            val scaleWidth = arguments.optionalFiniteDouble(SCALE_WIDTH_ARGUMENT) ?: DEFAULT_SCALE
            val scaleHeight = arguments.optionalFiniteDouble(SCALE_HEIGHT_ARGUMENT) ?: DEFAULT_SCALE
            if (scaleWidth <= 0.0 || scaleHeight <= 0.0) throw PluginError.InvalidArguments

            return RecognizeVisorCropRect(
                scaleWidth = scaleWidth,
                scaleHeight = scaleHeight,
                centerOffsetX = arguments.optionalFiniteDouble(OFFSET_X_ARGUMENT) ?: DEFAULT_OFFSET,
                centerOffsetY = arguments.optionalFiniteDouble(OFFSET_Y_ARGUMENT) ?: DEFAULT_OFFSET,
            )
        }

        private const val SCALE_WIDTH_ARGUMENT = "scaleWidth"
        private const val SCALE_HEIGHT_ARGUMENT = "scaleHeight"
        private const val OFFSET_X_ARGUMENT = "offsetX"
        private const val OFFSET_Y_ARGUMENT = "offsetY"
        private const val DEFAULT_SCALE = 1.0
        private const val DEFAULT_OFFSET = 0.0
    }
}
