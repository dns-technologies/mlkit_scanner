package com.dns_technologies.mlkit_scanner.scanner.models

/**
 * Describes a recognized barcode sent to Dart.
 *
 * @property rawValue Value as it was encoded in the barcode.
 * @property displayValue User-friendly representation of the barcode value, when available.
 * @property format Barcode format code understood by the Dart model.
 * @property valueType Barcode content type code understood by the Dart model.
 */
class Barcode(
    val rawValue: String,
    val displayValue: String?,
    val format: Int,
    val valueType: Int,
) {
    /** Converts this barcode into the Dart platform-channel payload. */
    fun toMap(): Map<String, Any?> = mapOf(
        "raw_value" to rawValue,
        "display_value" to displayValue,
        "format" to format,
        "value_type" to valueType,
    )
}
