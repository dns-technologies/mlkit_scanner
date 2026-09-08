package com.dns_technologies.mlkit_scanner.scanner

import com.dns_technologies.mlkit_scanner.PluginError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

internal class ScannerConfigurationTest {
    @Test
    fun `complete Dart snapshot parses defaults`() {
        assertEquals(ScannerConfiguration(), ScannerConfiguration.from(arguments))
    }

    @Test
    fun `required fields cannot be omitted`() {
        for (key in listOf("zoomRatio", "torchEnabled", "scanEnabled", "scanDelay")) {
            assertSame(PluginError.InvalidArguments, runCatching {
                ScannerConfiguration.from(arguments - key)
            }.exceptionOrNull())
        }
    }

    @Test
    fun `invalid snapshot never reaches hardware`() {
        for ((key, value) in listOf(
            "zoomRatio" to 0, "zoomRatio" to Double.NaN, "zoomRatio" to Double.MIN_VALUE,
            "scanDelay" to 0.5, "scanDelay" to 4_294_967_296L,
            "torchEnabled" to 1, "scanEnabled" to "true", "cropRect" to mapOf("scaleWidth" to 0),
        )) {
            assertSame(PluginError.InvalidArguments, runCatching {
                ScannerConfiguration.from(arguments + (key to value))
            }.exceptionOrNull())
        }
    }

    private val arguments = mapOf("zoomRatio" to 1.0, "torchEnabled" to false,
        "cropRect" to null, "scanEnabled" to false, "scanDelay" to 0)
}
