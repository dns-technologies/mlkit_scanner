package com.dns_technologies.mlkit_scanner.extensions

import com.google.mlkit.vision.barcode.Barcode

fun Barcode.toJson(): Map<String, Any?> {
    return mapOf(
            "rawValue" to rawValue
    )
}