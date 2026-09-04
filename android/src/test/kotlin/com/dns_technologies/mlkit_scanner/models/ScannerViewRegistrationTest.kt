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
                "initialZoom" to 0.75F,
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
        assertEquals(0.75, registration.initialZoom)
        assertEquals(true, registration.initialFlashEnabled)
        assertEquals(
            RecognizeVisorCropRect(0.5, 0.6, 0.1, -0.2),
            registration.initialCropRect,
        )
    }

    @Test
    fun `registration preserves absent optional controls`() {
        val registration = ScannerViewRegistration.from(mapOf("viewId" to 1))

        assertNull(registration.initialZoom)
        assertNull(registration.initialCropRect)
        assertNull(registration.initialFlashEnabled)
    }

    @Test
    fun `registration rejects invalid values`() {
        listOf(
            null,
            emptyMap<String, Any?>(),
            mapOf("viewId" to -1),
            mapOf("viewId" to 1.0),
            mapOf("viewId" to Long.MAX_VALUE),
            mapOf("viewId" to 1, "initialZoom" to -0.1),
            mapOf("viewId" to 1, "initialZoom" to 1.1),
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
