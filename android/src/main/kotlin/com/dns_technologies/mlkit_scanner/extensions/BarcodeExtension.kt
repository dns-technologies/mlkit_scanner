package com.dns_technologies.mlkit_scanner.extensions

import com.google.mlkit.vision.barcode.Barcode

fun Barcode.toJson(): Map<String, Any?> {
    val mappedFormat = when (format) {
        Barcode.FORMAT_UNKNOWN -> 0
        Barcode.FORMAT_ALL_FORMATS -> 0xFFFF
        else -> format
    }

    return mapOf(
            "raw_value" to rawValue,
            "display_value" to displayValue,
            "format" to mappedFormat,
            "value_type" to valueType,
    )
}