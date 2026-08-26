package com.dns_technologies.mlkit_scanner.commands.base

import com.dns_technologies.mlkit_scanner.PluginError
import com.dns_technologies.mlkit_scanner.scanner.models.RecognizeVisorCropRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

internal class ScannerMethodArgumentsTest {
    @Test
    fun `camera initialization parses optional typed controls`() {
        val arguments = ScannerMethodArguments.cameraInitialization(
            mapOf(
                "viewId" to 42L,
                "initialZoom" to 0.75F,
                "initialCropRect" to mapOf(
                    "scaleWidth" to 0.5F,
                    "scaleHeight" to 0.6,
                    "offsetX" to 0.1,
                    "offsetY" to -0.2,
                ),
            ),
        )

        assertEquals(42, arguments.viewId)
        assertEquals(0.75, arguments.initialZoom)
        assertEquals(
            RecognizeVisorCropRect(0.5, 0.6, 0.1, -0.2),
            arguments.initialCropRect,
        )
    }

    @Test
    fun `camera initialization preserves absent optional controls`() {
        val arguments = ScannerMethodArguments.cameraInitialization(mapOf("viewId" to 1))

        assertNull(arguments.initialZoom)
        assertNull(arguments.initialCropRect)
    }

    @Test
    fun `scan options require barcode type and non-negative integer delay`() {
        assertEquals(
            ScannerMethodArguments.ScanOptions(periodMs = 150),
            ScannerMethodArguments.scanOptions(mapOf("type" to 0, "delay" to 150L)),
        )

        assertInvalid { ScannerMethodArguments.scanOptions(mapOf("type" to 1, "delay" to 0)) }
        assertInvalid { ScannerMethodArguments.scanOptions(mapOf("type" to 0, "delay" to -1)) }
        assertInvalid { ScannerMethodArguments.scanOptions(mapOf("type" to 0, "delay" to 1.5)) }
    }

    @Test
    fun `zoom rejects non-finite and out-of-range values`() {
        assertEquals(0.5F, ScannerMethodArguments.zoom(0.5))

        listOf(Double.NaN, Double.POSITIVE_INFINITY, -0.01, 1.01, "0.5").forEach { value ->
            assertInvalid { ScannerMethodArguments.zoom(value) }
        }
    }

    @Test
    fun `crop uses defaults and rejects invalid components`() {
        assertEquals(RecognizeVisorCropRect(), ScannerMethodArguments.cropRect(emptyMap<String, Any?>()))

        listOf(
            mapOf("scaleWidth" to 0.0),
            mapOf("scaleHeight" to -1.0),
            mapOf("offsetX" to Double.NaN),
            mapOf("offsetY" to Double.NEGATIVE_INFINITY),
            mapOf("scaleWidth" to "0.5"),
        ).forEach { arguments ->
            assertInvalid { ScannerMethodArguments.cropRect(arguments) }
        }
    }

    @Test
    fun `view identity rejects missing fractional negative and overflowing values`() {
        listOf(
            null,
            emptyMap<String, Any?>(),
            mapOf("viewId" to -1),
            mapOf("viewId" to 1.5),
            mapOf("viewId" to Long.MAX_VALUE),
            mapOf("viewId" to 2_147_483_648F),
            mapOf("viewId" to 1e20),
        ).forEach { arguments ->
            assertInvalid { ScannerMethodArguments.viewId(arguments) }
        }
    }

    private fun assertInvalid(block: () -> Unit) {
        assertSame(PluginError.InvalidArguments, runCatching(block).exceptionOrNull())
    }
}
