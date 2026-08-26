package com.dns_technologies.mlkit_scanner.scanner.utils

import com.dns_technologies.mlkit_scanner.scanner.components.camera.CameraFrame
import com.dns_technologies.mlkit_scanner.scanner.components.camera.Rect
import com.dns_technologies.mlkit_scanner.scanner.models.RecognizeVisorCropRect
import org.junit.Assert.assertEquals
import org.junit.Test

internal class ScanAreaCalculatorTest {
    @Test
    fun `full visor produces full frame`() {
        assertEquals(
            Rect(0, 0, 720, 1280),
            ScanAreaCalculator.calculate(metadata(0), RecognizeVisorCropRect()),
        )
    }

    @Test
    fun `crop mapping is preserved for zero degree rotation`() {
        assertCrop(0, Rect(252, 320, 612, 832))
    }

    @Test
    fun `crop mapping is preserved for ninety degree rotation`() {
        assertCrop(90, Rect(180, 192, 468, 832))
    }

    @Test
    fun `crop mapping is preserved for one hundred eighty degree rotation`() {
        assertCrop(180, Rect(108, 448, 468, 960))
    }

    @Test
    fun `crop mapping is preserved for two hundred seventy degree rotation`() {
        assertCrop(270, Rect(252, 448, 540, 1088))
    }

    @Test
    fun `crop larger than preview is clipped to frame bounds`() {
        val crop = ScanAreaCalculator.calculate(
            frame(width = 721, height = 1281, rotationDegree = 0),
            RecognizeVisorCropRect(
                scaleWidth = 2.0,
                scaleHeight = 2.0,
                centerOffsetX = 1.0,
                centerOffsetY = 1.0,
            ),
        )

        assertEquals(Rect(0, 0, 720, 1280), crop)
    }

    @Test
    fun `fully outside crop returns empty rectangle for every rotation`() {
        listOf(0, 90, 180, 270).forEach { rotationDegree ->
            assertEquals(
                Rect(0, 0, 0, 0),
                ScanAreaCalculator.calculate(
                    metadata(rotationDegree),
                    RecognizeVisorCropRect(
                        scaleWidth = 0.2,
                        scaleHeight = 0.2,
                        centerOffsetX = 3.0,
                        centerOffsetY = 3.0,
                    ),
                ),
            )
        }
    }

    @Test
    fun `partially outside crop is clipped to even frame bounds`() {
        assertEquals(
            Rect(504, 0, 720, 1280),
            ScanAreaCalculator.calculate(
                metadata(0),
                RecognizeVisorCropRect(
                    scaleWidth = 0.5,
                    scaleHeight = 1.0,
                    centerOffsetX = 0.9,
                ),
            ),
        )
    }

    @Test
    fun `full visor uses CameraX preview crop`() {
        assertEquals(
            Rect(10, 20, 710, 1260),
            ScanAreaCalculator.calculate(
                frame(cropRect = Rect(10, 20, 710, 1260)),
                RecognizeVisorCropRect(),
            ),
        )
    }

    @Test
    fun `odd CameraX preview crop is safely restricted to even YUV bounds`() {
        assertEquals(
            Rect(12, 22, 708, 1258),
            ScanAreaCalculator.calculate(
                frame(cropRect = Rect(11, 21, 709, 1259)),
                RecognizeVisorCropRect(),
            ),
        )
    }

    @Test
    fun `non finite mapped coordinates return empty rectangle`() {
        assertEquals(
            Rect(0, 0, 0, 0),
            ScanAreaCalculator.calculate(
                metadata(0),
                RecognizeVisorCropRect(centerOffsetX = Double.MAX_VALUE),
            ),
        )
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
        )
        assertEquals(expected, crop)
    }

    private fun metadata(rotationDegree: Int): CameraFrame = frame(rotationDegree = rotationDegree)

    private fun frame(
        width: Int = 720,
        height: Int = 1280,
        rotationDegree: Int = 0,
        cropRect: Rect = Rect(0, 0, width, height),
    ): CameraFrame = object : CameraFrame {
        override val width = width
        override val height = height
        override val rotationDegree = rotationDegree
        override val cropRect = cropRect

        override fun <T> useNv21(
            cropRect: Rect?,
            block: (ByteArray, Int, Int, Int) -> T,
        ): T = error("Frame access is not expected")

        override fun close() = Unit
    }
}
