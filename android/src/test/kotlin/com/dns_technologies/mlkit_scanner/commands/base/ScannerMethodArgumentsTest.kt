package com.dns_technologies.mlkit_scanner.commands.base

import com.dns_technologies.mlkit_scanner.PluginError
import com.dns_technologies.mlkit_scanner.scanner.models.RecognizeVisorCropRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

internal class ScannerMethodArgumentsTest {
    @Test
    fun `view creation parses optional typed controls`() {
        val arguments = ScannerMethodArguments.viewRegistration(
            mapOf(
                "viewId" to 42L,
                "initialZoomRatio" to 2.0F,
                "initialFlashEnabled" to true,
                "initialCropRect" to mapOf(
                    "scaleWidth" to 0.5F,
                    "scaleHeight" to 0.6,
                    "offsetX" to 0.1,
                    "offsetY" to -0.2,
                ),
            ),
        )

        assertEquals(42, arguments.viewId)
        assertEquals(2.0, arguments.initialZoomRatio)
        assertEquals(true, arguments.initialFlashEnabled)
        assertEquals(
            RecognizeVisorCropRect(0.5, 0.6, 0.1, -0.2),
            arguments.initialCropRect,
        )
    }

    @Test
    fun `view creation preserves absent optional controls`() {
        val arguments = ScannerMethodArguments.viewRegistration(mapOf("viewId" to 1))

        assertNull(arguments.initialZoomRatio)
        assertNull(arguments.initialCropRect)
        assertNull(arguments.initialFlashEnabled)
        assertInvalid {
            ScannerMethodArguments.viewRegistration(
                mapOf("viewId" to 1, "initialFlashEnabled" to 1),
            )
        }
    }

    @Test
    fun `initial zoom ratio requires a positive finite Float representation`() {
        listOf(Double.MIN_VALUE, Double.MAX_VALUE).forEach { initialZoomRatio ->
            assertInvalid {
                ScannerMethodArguments.viewRegistration(
                    mapOf("viewId" to 1, "initialZoomRatio" to initialZoomRatio),
                )
            }
        }
    }

    @Test
    fun `scan options require barcode type and non-negative integer delay`() {
        assertEquals(
            ScannerMethodArguments.ScanOptions(viewId = 42, periodMs = 150),
            ScannerMethodArguments.scanOptions(
                mapOf("viewId" to 42, "type" to 0, "delay" to 150L),
            ),
        )

        assertInvalid {
            ScannerMethodArguments.scanOptions(mapOf("viewId" to 42, "type" to 1, "delay" to 0))
        }
        assertInvalid {
            ScannerMethodArguments.scanOptions(mapOf("viewId" to 42, "type" to 0, "delay" to -1))
        }
        assertInvalid {
            ScannerMethodArguments.scanOptions(mapOf("viewId" to 42, "type" to 0, "delay" to 1.5))
        }
        assertInvalid {
            ScannerMethodArguments.scanOptions(mapOf("type" to 0, "delay" to 0))
        }
    }

    @Test
    fun `zoom ratio accepts positive finite values and rejects invalid values`() {
        assertEquals(
            ScannerMethodArguments.ViewValue(viewId = 42, value = 3.0F),
            ScannerMethodArguments.zoomRatio(mapOf("viewId" to 42, "value" to 3.0)),
        )

        listOf(
            Double.NaN,
            Double.POSITIVE_INFINITY,
            Double.MIN_VALUE,
            Double.MAX_VALUE,
            -0.01,
            0.0,
            "2.0",
        ).forEach { value ->
            assertInvalid {
                ScannerMethodArguments.zoomRatio(mapOf("viewId" to 42, "value" to value))
            }
        }
        assertInvalid { ScannerMethodArguments.zoomRatio(mapOf("value" to 2.0)) }
    }

    @Test
    fun `crop uses defaults and rejects invalid components`() {
        assertEquals(
            ScannerMethodArguments.ViewValue(
                viewId = 42,
                value = RecognizeVisorCropRect(),
            ),
            ScannerMethodArguments.cropRect(
                mapOf("viewId" to 42, "cropRect" to emptyMap<String, Any?>()),
            ),
        )

        listOf(
            mapOf("scaleWidth" to 0.0),
            mapOf("scaleHeight" to -1.0),
            mapOf("offsetX" to Double.NaN),
            mapOf("offsetY" to Double.NEGATIVE_INFINITY),
            mapOf("scaleWidth" to "0.5"),
        ).forEach { arguments ->
            assertInvalid {
                ScannerMethodArguments.cropRect(
                    mapOf("viewId" to 42, "cropRect" to arguments),
                )
            }
        }
        assertInvalid { ScannerMethodArguments.cropRect(mapOf("viewId" to 42)) }
    }

    @Test
    fun `scan delay is scoped to a view and rejects invalid values`() {
        assertEquals(
            ScannerMethodArguments.ViewValue(viewId = 42, value = 150),
            ScannerMethodArguments.scanDelay(mapOf("viewId" to 42, "delay" to 150L)),
        )
        assertInvalid { ScannerMethodArguments.scanDelay(mapOf("viewId" to 42, "delay" to -1)) }
        assertInvalid { ScannerMethodArguments.scanDelay(mapOf("delay" to 150)) }
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

    @Test
    fun `view identity preserves exact integral values across supported number types`() {
        listOf<Number>(
            42.toByte(),
            42.toShort(),
            42,
            42L,
            42.0F,
            42.0,
        ).forEach { value ->
            assertEquals(42, ScannerMethodArguments.viewId(mapOf("viewId" to value)))
        }
        assertEquals(
            Int.MAX_VALUE,
            ScannerMethodArguments.viewId(mapOf("viewId" to Int.MAX_VALUE.toDouble())),
        )
    }

    private fun assertInvalid(block: () -> Unit) {
        assertSame(PluginError.InvalidArguments, runCatching(block).exceptionOrNull())
    }
}
