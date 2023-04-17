package com.dns_technologies.mlkit_scanner.models

/** Describes initial parameters to initialize scanner  */
data class InitialScannerParameters(
    val initialZoom: Double? = null,
    val initialCropArea: RecognizeVisorCropRect? = null,
) {
    companion object {
        fun fromMap(map: Map<String, Any?>?): InitialScannerParameters? {
            if (map == null) return null

            return InitialScannerParameters(
                (map["initialZoom"] as Double?),
                if (map["initialCropRect"] is Map<*, *>) RecognizeVisorCropRect.fromMap(map["initialCropRect"] as Map<String, Any?>) else null,
            )
        }
    }
}