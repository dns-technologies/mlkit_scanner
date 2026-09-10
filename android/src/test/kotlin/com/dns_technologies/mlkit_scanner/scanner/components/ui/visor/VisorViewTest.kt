package com.dns_technologies.mlkit_scanner.scanner.components.ui.visor

import com.dns_technologies.mlkit_scanner.scanner.components.camera.Rect
import com.dns_technologies.mlkit_scanner.scanner.models.RecognizeVisorCropRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
internal class VisorViewTest {
    @Test
    fun `calculate visor bounds respects scale and normalized center offset`() {
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

        assertEquals(Rect(100, 10, 200, 50), bounds)
    }

    @Test
    fun `crop is retained until layout resolves its geometry`() {
        val view = VisorView(RuntimeEnvironment.getApplication())
        val changes = mutableListOf<Triple<Rect, Int, Int>>()
        view.onCropBoundsChanged = { bounds, width, height ->
            changes += Triple(bounds, width, height)
        }

        view.setCropArea(RecognizeVisorCropRect(scaleWidth = 0.5, scaleHeight = 0.5))
        assertNull(view.cropBounds)

        view.layout(0, 0, 200, 100)

        assertEquals(Rect(50, 25, 150, 75), view.cropBounds)
        assertEquals(
            listOf(Triple(Rect(50, 25, 150, 75), 200, 100)),
            changes,
        )
    }

    @Test
    fun `active state changes only when the requested value changes`() {
        val view = VisorView(RuntimeEnvironment.getApplication())
        assertFalse(view.isActive)

        view.isActive = true
        assertTrue(view.isActive)

        view.isActive = true
        assertTrue(view.isActive)
    }
}
