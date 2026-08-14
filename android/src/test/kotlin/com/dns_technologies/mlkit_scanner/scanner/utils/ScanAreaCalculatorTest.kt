package com.dns_technologies.mlkit_scanner.scanner.utils

import com.dns_technologies.mlkit_scanner.scanner.components.camera.CameraFrame
import com.dns_technologies.mlkit_scanner.scanner.components.camera.Rect
import com.dns_technologies.mlkit_scanner.scanner.models.RecognizeVisorCropRect
import com.google.mlkit.vision.common.InputImage
import org.junit.Assert.assertEquals
import org.junit.Test

internal class ScanAreaCalculatorTest {
    @Test
    fun `full visor produces full frame`() {
        assertEquals(
            Rect(0, 0, 720, 1280),
            ScanAreaCalculator.calculate(metadata(0), RecognizeVisorCropRect(), DEFAULT_SCALE),
        )
    }

    @Test
    fun `crop mapping is preserved for zero degree rotation`() {
        assertCrop(0, Rect(252, 268, 612, 884))
    }

    @Test
    fun `crop mapping is preserved for ninety degree rotation`() {
        assertCrop(90, Rect(150, 192, 496, 832))
    }

    @Test
    fun `crop mapping is preserved for one hundred eighty degree rotation`() {
        assertCrop(180, Rect(108, 396, 468, 1012))
    }

    @Test
    fun `crop mapping is preserved for two hundred seventy degree rotation`() {
        assertCrop(270, Rect(222, 448, 568, 1088))
    }

    @Test
    fun `crop is clipped and aligned to even frame bounds`() {
        val crop = ScanAreaCalculator.calculate(
            frame(width = 721, height = 1281, rotationDegree = 0),
            RecognizeVisorCropRect(
                scaleWidth = 2.0,
                scaleHeight = 2.0,
                centerOffsetX = 1.0,
                centerOffsetY = 1.0,
            ),
            DEFAULT_SCALE,
        )

        assertEquals(Rect(0, 0, 720, 1280), crop)
    }

    private fun assertCrop(rotationDegree: Int, expected: Rect) {
        val crop = ScanAreaCalculator.calculate(
            metadata(rotationDegree),
            RecognizeVisorCropRect(
                scaleWidth = 0.5,
                scaleHeight = 0.4,
                centerOffsetX = 0.2,
                centerOffsetY = -0.1,
            ),
            DEFAULT_SCALE,
        )
        assertEquals(expected, crop)
    }

    private fun metadata(rotationDegree: Int): CameraFrame = frame(rotationDegree = rotationDegree)

    private fun frame(
        width: Int = 720,
        height: Int = 1280,
        rotationDegree: Int = 0,
    ): CameraFrame = object : CameraFrame {
        override val width = width
        override val height = height
        override val rotationDegree = rotationDegree

        override fun toInputImage(cropRect: Rect?): InputImage =
            error("Frame materialization is not expected")

        override fun close() = Unit
    }

    private companion object {
        val DEFAULT_SCALE = Pair(1.0, 1.0)
    }
}
