package com.dns_technologies.mlkit_scanner.models

/** Describes initial parameters to initialize scanner  */
data class ScannerParameters(
    val zoom: Double? = null,
    val cropRect: RecognizeVisorCropRect? = null,
) {
    constructor(map: Map<String, Any?>) : this(
        (map["initialZoom"] as Double?),
        if (map["initialCropRect"] is Map<*, *>) RecognizeVisorCropRect.fromMap(map["initialCropRect"] as Map<String, Any?>) else null,
    )
}