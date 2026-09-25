package com.dns_technologies.mlkit_scanner.scanner.models

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
            return RecognizeVisorCropRect(
                scaleWidth = arguments.optionalFiniteDouble(SCALE_WIDTH_ARGUMENT) ?: DEFAULT_SCALE,
                scaleHeight = arguments.optionalFiniteDouble(SCALE_HEIGHT_ARGUMENT) ?: DEFAULT_SCALE,
                centerOffsetX = arguments.optionalFiniteDouble(OFFSET_X_ARGUMENT) ?: DEFAULT_OFFSET,
                centerOffsetY = arguments.optionalFiniteDouble(OFFSET_Y_ARGUMENT) ?: DEFAULT_OFFSET,
            )
        }

        /** Channel key for recognition width relative to the preview. */
        private const val SCALE_WIDTH_ARGUMENT = "scaleWidth"
        /** Channel key for recognition height relative to the preview. */
        private const val SCALE_HEIGHT_ARGUMENT = "scaleHeight"
        /** Channel key for the horizontal offset from the preview center. */
        private const val OFFSET_X_ARGUMENT = "offsetX"
        /** Channel key for the vertical offset from the preview center. */
        private const val OFFSET_Y_ARGUMENT = "offsetY"
        /** Full-preview fraction used when a crop scale is omitted. */
        private const val DEFAULT_SCALE = 1.0
        /** Centered position used when a crop offset is omitted. */
        private const val DEFAULT_OFFSET = 0.0
    }
}
