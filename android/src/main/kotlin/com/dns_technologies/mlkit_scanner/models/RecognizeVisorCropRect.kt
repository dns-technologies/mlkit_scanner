package com.dns_technologies.mlkit_scanner.models

/**
 * Defines a rectangle of the camera image recognition area
 * By default the recognition rectangle is equal to the rectangle of the scanner camera widget
 * */
data class RecognizeVisorCropRect(
        val scaleWidth: Double = 1.0,
        val scaleHeight: Double = 1.0,
        val centerOffsetX: Double = 0.0,
        val centerOffsetY: Double = 0.0) {
    companion object {
        fun fromMap(map: Map<String, Any?>): RecognizeVisorCropRect {
            return RecognizeVisorCropRect(
                    (map["scaleWidth"] as Double?) ?: 1.0,
                    (map["scaleHeight"] as Double?) ?: 1.0,
                    (map["offsetX"] as Double?) ?: 0.0,
                    (map["offsetY"] as Double?) ?: 0.0)
        }
    }
    
    fun shouldCrop(): Boolean = scaleWidth != 1.0 
            || scaleHeight != 1.0
            || centerOffsetX != 0.0
            || centerOffsetY != 0.0
}