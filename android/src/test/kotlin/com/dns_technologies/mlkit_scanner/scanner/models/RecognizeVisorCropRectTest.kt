package com.dns_technologies.mlkit_scanner.scanner.models

import com.dns_technologies.mlkit_scanner.PluginError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

internal class RecognizeVisorCropRectTest {
    @Test
    fun `map factory applies defaults`() {
        assertEquals(
            RecognizeVisorCropRect(),
            RecognizeVisorCropRect.fromMap(emptyMap<String, Any?>()),
        )
    }

    @Test
    fun `map factory rejects invalid components`() {
        listOf(
            mapOf("scaleWidth" to 0.0),
            mapOf("scaleHeight" to -1.0),
            mapOf("offsetX" to Double.NaN),
            mapOf("offsetY" to Double.NEGATIVE_INFINITY),
            mapOf("scaleWidth" to "0.5"),
        ).forEach { arguments ->
            assertSame(
                PluginError.InvalidArguments,
                runCatching { RecognizeVisorCropRect.fromMap(arguments) }.exceptionOrNull(),
            )
        }
    }
}
