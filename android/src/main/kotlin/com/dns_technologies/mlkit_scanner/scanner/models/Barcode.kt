package com.dns_technologies.mlkit_scanner.scanner.models

/**
 * Recognized barcode contents and classification codes.
 *
 * @property rawValue Value as it was encoded in the barcode.
 * @property displayValue User-friendly representation of the barcode value, when available.
 * @property format Stable barcode format code.
 * @property valueType Stable barcode content type code.
 */
class Barcode(
    val rawValue: String,
    val displayValue: String?,
    val format: Int,
    val valueType: Int,
) {
    /** Serializes barcode values into a string-keyed map. */
    fun toMap(): Map<String, Any?> =
        mapOf(
            "raw_value" to rawValue,
            "display_value" to displayValue,
            "format" to format,
            "value_type" to valueType,
        )
}
