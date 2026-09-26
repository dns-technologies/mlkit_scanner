package com.dns_technologies.mlkit_scanner.scanner.components.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraFrameTest {
    @Test
    fun `default crop covers the complete unrotated frame`() {
        val frame = TestCameraFrame(width = 640, height = 480)

        assertEquals(Rect(0, 0, 640, 480), frame.cropRect)
    }
}

private class TestCameraFrame(
    override val width: Int,
    override val height: Int,
) : CameraFrame {
    override val rotationDegree: Int = 90

    override fun <T> useNv21(
        cropRect: Rect?,
        block: (bytes: ByteArray, width: Int, height: Int, rotationDegree: Int) -> T,
    ): T = error("Not needed by this test")

    override fun close() = Unit
}
