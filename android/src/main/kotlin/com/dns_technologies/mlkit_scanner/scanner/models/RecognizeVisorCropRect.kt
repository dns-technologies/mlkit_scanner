package com.dns_technologies.mlkit_scanner.scanner.models

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
)
