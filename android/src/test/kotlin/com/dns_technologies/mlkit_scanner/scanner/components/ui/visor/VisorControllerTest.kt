package com.dns_technologies.mlkit_scanner.scanner.components.ui.visor

import com.dns_technologies.mlkit_scanner.scanner.models.RecognizeVisorCropRect
import org.junit.Assert.assertEquals
import org.junit.Test

internal class VisorControllerTest {
    @Test
    fun `calculate visor bounds respects size scale and offset`() {
        val bounds = calculateVisorBounds(
            containerWidth = 200,
            containerHeight = 100,
            cropArea = RecognizeVisorCropRect(
                scaleWidth = 0.5,
                scaleHeight = 0.4,
                centerOffsetX = 0.5,
                centerOffsetY = -0.4,
            ),
        )

        assertEquals(100, bounds.left)
        assertEquals(10, bounds.top)
        assertEquals(200, bounds.right)
        assertEquals(50, bounds.bottom)
    }
}
