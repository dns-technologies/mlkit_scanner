package com.dns_technologies.mlkit_scanner.scanner.models

/**
 * Defines the recognized area inside the scanner camera preview.
 *
 * By default, the recognition rectangle matches the full scanner widget.
 */
data class RecognizeVisorCropRect(
    val scaleWidth: Double = 1.0,
    val scaleHeight: Double = 1.0,
    val centerOffsetX: Double = 0.0,
    val centerOffsetY: Double = 0.0,
) {
    companion object {
        /** Creates [RecognizeVisorCropRect] from a method channel argument map. */
        fun fromMap(map: Map<String, Any?>): RecognizeVisorCropRect {
            return RecognizeVisorCropRect(
                (map["scaleWidth"] as Double?) ?: 1.0,
                (map["scaleHeight"] as Double?) ?: 1.0,
                (map["offsetX"] as Double?) ?: 0.0,
                (map["offsetY"] as Double?) ?: 0.0,
            )
        }
    }

    /** Returns true when the scanner should crop frames before analysis. */
    fun shouldCrop(): Boolean = scaleWidth != 1.0 
            || scaleHeight != 1.0
            || centerOffsetX != 0.0
            || centerOffsetY != 0.0
}
