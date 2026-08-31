package com.dns_technologies.mlkit_scanner.scanner.components.ui.visor

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import com.dns_technologies.mlkit_scanner.scanner.models.RecognizeVisorCropRect
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockConstruction
import org.mockito.Mockito.verify

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

    @Test
    fun `crop is retained by visor before layout is available`() {
        val boundsView = mock(FrameLayout::class.java)
        doReturn(mock(Context::class.java)).`when`(boundsView).context

        mockConstruction(VisorView::class.java).use { construction ->
            val controller = VisorController(
                boundsView = boundsView,
                anchorView = mock(View::class.java),
                onCenterChanged = { _, _ -> },
            )
            val cropArea = RecognizeVisorCropRect(scaleWidth = 0.5)
            controller.setCropArea(cropArea)
            val visor = construction.constructed().single()

            verify(visor).setCropArea(cropArea)
        }
    }
}
