package com.dns_technologies.mlkit_scanner.scanner.models

/**
 * Describes initial parameters used during scanner initialization.
 *
 * @property zoom Optional normalized camera zoom value.
 * @property cropRect Optional initial scanner recognition area.
 */
data class InitialScannerParameters(
    val zoom: Double? = null,
    val cropRect: RecognizeVisorCropRect? = null,
) {
    /** Creates [InitialScannerParameters] from a method channel argument map. */
    constructor(map: Map<String, Any?>) : this(
        (map["initialZoom"] as Double?),
        (map["initialCropRect"] as? Map<*, *>)?.let(RecognizeVisorCropRect::fromMap),
    )
}
