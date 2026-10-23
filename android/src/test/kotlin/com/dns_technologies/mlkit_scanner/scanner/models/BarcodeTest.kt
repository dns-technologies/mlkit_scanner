package com.dns_technologies.mlkit_scanner.scanner.models

import com.google.mlkit.vision.barcode.common.Barcode as MlkitBarcode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

internal class BarcodeTest {
    @Test
    fun `toMap uses the Flutter barcode wire keys`() {
        val barcode = Barcode(
            rawValue = "raw",
            displayValue = "display",
            format = MlkitBarcode.FORMAT_QR_CODE,
            valueType = MlkitBarcode.TYPE_URL,
        )

        assertEquals(
            mapOf(
                "raw_value" to "raw",
                "display_value" to "display",
                "format" to MlkitBarcode.FORMAT_QR_CODE,
                "value_type" to MlkitBarcode.TYPE_URL,
            ),
            barcode.toMap(),
        )
    }

    @Test
    fun `toMap preserves a nullable display value`() {
        val map = Barcode("raw", null, 0, 0).toMap()

        assertNull(map["display_value"])
    }
}
