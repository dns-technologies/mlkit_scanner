package com.dns_technologies.mlkit_scanner.scanner.models

/** Describes a recognized barcode value sent to Dart. */
class Barcode(
    private val value: String,
) {
    /** Converts this barcode into the legacy Dart payload shape. */
    fun toMap(): Map<String, Any?> = mapOf(
        "raw_value" to value,
        "display_value" to value,
        "format" to UNKNOWN_FORMAT,
        "value_type" to UNKNOWN_VALUE_TYPE,
    )

    private companion object {
        const val UNKNOWN_FORMAT = 0
        const val UNKNOWN_VALUE_TYPE = 0
    }
}
