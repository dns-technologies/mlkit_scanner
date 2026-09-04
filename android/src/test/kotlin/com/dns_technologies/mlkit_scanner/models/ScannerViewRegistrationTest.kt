package com.dns_technologies.mlkit_scanner.models

import com.dns_technologies.mlkit_scanner.PluginError
import com.dns_technologies.mlkit_scanner.scanner.models.RecognizeVisorCropRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

internal class ScannerViewRegistrationTest {
    @Test
    fun `registration maps optional controls`() {
        val registration = ScannerViewRegistration.from(
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

        assertEquals(42, registration.viewId)
        assertEquals(2.0, registration.initialZoomRatio)
        assertEquals(true, registration.initialFlashEnabled)
        assertEquals(
            RecognizeVisorCropRect(0.5, 0.6, 0.1, -0.2),
            registration.initialCropRect,
        )
    }

    @Test
    fun `registration preserves absent optional controls`() {
        val registration = ScannerViewRegistration.from(mapOf("viewId" to 1))

        assertNull(registration.initialZoomRatio)
        assertNull(registration.initialCropRect)
        assertNull(registration.initialFlashEnabled)
    }

    @Test
    fun `registration accepts exact view ids across channel number types`() {
        listOf<Number>(
            42.toByte(),
            42.toShort(),
            42,
            42L,
            42.0F,
            42.0,
        ).forEach { viewId ->
            assertEquals(42, ScannerViewRegistration.from(mapOf("viewId" to viewId)).viewId)
        }
        assertEquals(
            Int.MAX_VALUE,
            ScannerViewRegistration.from(mapOf("viewId" to Int.MAX_VALUE.toDouble())).viewId,
        )
    }

    @Test
    fun `registration rejects invalid values`() {
        listOf(
            null,
            emptyMap<String, Any?>(),
            mapOf("viewId" to -1),
            mapOf("viewId" to 1.5),
            mapOf("viewId" to Long.MAX_VALUE),
            mapOf("viewId" to Double.NaN),
            mapOf("viewId" to 2_147_483_648F),
            mapOf("viewId" to 1e20),
            mapOf("viewId" to 1, "initialZoomRatio" to 0.0),
            mapOf("viewId" to 1, "initialZoomRatio" to Double.MIN_VALUE),
            mapOf("viewId" to 1, "initialZoomRatio" to Double.MAX_VALUE),
            mapOf("viewId" to 1, "initialFlashEnabled" to 1),
            mapOf("viewId" to 1, "initialCropRect" to "invalid"),
        ).forEach { arguments ->
            assertSame(
                PluginError.InvalidArguments,
                runCatching { ScannerViewRegistration.from(arguments) }.exceptionOrNull(),
            )
        }
    }
}
