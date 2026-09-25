package com.dns_technologies.mlkit_scanner.scanner

import com.dns_technologies.mlkit_scanner.PluginError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

internal class ScannerConfigurationTest {
    @Test
    fun `camera settings parse without Dart recognition preferences`() {
        assertEquals(ScannerConfiguration(), ScannerConfiguration.from(arguments))
    }

    @Test
    fun `required fields cannot be omitted`() {
        for (key in listOf("zoomRatio", "torchEnabled")) {
            assertSame(PluginError.InvalidArguments, runCatching {
                ScannerConfiguration.from(arguments - key)
            }.exceptionOrNull())
        }
    }

    @Test
    fun `malformed fields cannot be decoded`() {
        for ((key, value) in listOf(
            "zoomRatio" to true, "zoomRatio" to Double.NaN, "zoomRatio" to Double.MIN_VALUE,
            "torchEnabled" to 1, "cropRect" to mapOf("scaleWidth" to "bad"),
        )) {
            assertSame(PluginError.InvalidArguments, runCatching {
                ScannerConfiguration.from(arguments + (key to value))
            }.exceptionOrNull())
        }
    }

    @Test
    fun `decoding leaves application ranges to Dart`() {
        val decoded = ScannerConfiguration.from(arguments + mapOf(
            "zoomRatio" to 0.0, "cropRect" to mapOf("scaleWidth" to -1.0),
        ))
        assertEquals(0F, decoded.zoomRatio)
        assertEquals(-1.0, decoded.cropArea?.scaleWidth)
    }

    private val arguments = mapOf("zoomRatio" to 1.0, "torchEnabled" to false,
        "cropRect" to null)
}
