package com.dns_technologies.mlkit_scanner.scanner.components.camera.x

import com.dns_technologies.mlkit_scanner.scanner.components.camera.Rect
import org.junit.Assert.assertEquals
import org.junit.Test

internal class PreviewCropCalculatorTest {
    @Test
    fun `matching preview aspect preserves source crop for every rotation`() {
        val source = Rect(10, 20, 1610, 920)

        assertEquals(source, PreviewCropCalculator.calculate(source, 0, 1600, 900))
        assertEquals(source, PreviewCropCalculator.calculate(source, 90, 900, 1600))
        assertEquals(source, PreviewCropCalculator.calculate(source, 180, 1600, 900))
        assertEquals(source, PreviewCropCalculator.calculate(source, 270, 900, 1600))
    }

    @Test
    fun `square preview crops horizontal source bounds for unrotated frame`() {
        assertEquals(
            Rect(350, 0, 1250, 900),
            PreviewCropCalculator.calculate(Rect(0, 0, 1600, 900), 0, 1000, 1000),
        )
    }

    @Test
    fun `resized preview preserves non-zero source origin`() {
        assertEquals(
            Rect(450, 200, 1350, 1100),
            PreviewCropCalculator.calculate(Rect(100, 200, 1700, 1100), 0, 1000, 1000),
        )
    }

    @Test
    fun `square preview crops vertical source bounds for unrotated frame`() {
        assertEquals(
            Rect(0, 350, 900, 1250),
            PreviewCropCalculator.calculate(Rect(0, 0, 900, 1600), 0, 1000, 1000),
        )
    }

    @Test
    fun `rotated frame maps preview vertical inset onto source horizontal axis`() {
        assertEquals(
            Rect(350, 0, 1250, 900),
            PreviewCropCalculator.calculate(Rect(0, 0, 1600, 900), 90, 1000, 1000),
        )
    }

    @Test
    fun `missing preview bounds preserve CameraX crop`() {
        val source = Rect(10, 20, 1610, 920)

        assertEquals(source, PreviewCropCalculator.calculate(source, 0, 0, 900))
        assertEquals(source, PreviewCropCalculator.calculate(source, 0, 1600, 0))
    }
}
