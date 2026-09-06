package com.dns_technologies.mlkit_scanner.scanner.components.camera.x

import android.graphics.Rect as AndroidRect
import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import com.dns_technologies.mlkit_scanner.scanner.components.camera.Rect
import com.dns_technologies.mlkit_scanner.scanner.utils.ImageProxyNv21Converter
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

internal class XCameraFrameCropTest {
    private val converter = mock(ImageProxyNv21Converter::class.java)

    @Test
    fun `narrow preview keeps a representable non-empty crop inside the source`() {
        val source = Rect(10, 20, 12, 30)
        val crop = cropFor(source, 0, 1, 100)

        assertFalse(crop.isEmpty)
        assertEquals(source.top, crop.top)
        assertEquals(source.bottom, crop.bottom)
    }

    @Test
    fun `crop stays centered non-empty and inside source across geometry and rotations`() {
        val sizes = listOf(1, 2, 3, 7, 90, 901, 1600)
        for (width in sizes) for (height in sizes) {
            val source = Rect(10, 20, 10 + width, 20 + height)
            for (previewWidth in sizes) for (previewHeight in sizes) {
                for (rotation in listOf(0, 90, 180, 270)) {
                    val crop = cropFor(source, rotation, previewWidth, previewHeight)
                    assertFalse("$source -> $crop at $rotation", crop.isEmpty)
                    assertTrue(crop.left >= source.left && crop.right <= source.right)
                    assertTrue(crop.top >= source.top && crop.bottom <= source.bottom)
                    assertEquals(source.left + source.right, crop.left + crop.right)
                    assertEquals(source.top + source.bottom, crop.top + crop.bottom)
                    assertTrue(crop.width == source.width || crop.height == source.height)
                }
            }
        }
    }

    @Test
    fun `rotating preview and frame together preserves source crop`() {
        val source = Rect(10, 20, 913, 1621)
        val crop = cropFor(source, 0, 300, 900)
        assertEquals(crop, cropFor(source, 180, 300, 900))
        assertEquals(crop, cropFor(source, 90, 900, 300))
        assertEquals(crop, cropFor(source, 270, 900, 300))
    }

    @Test
    fun `empty source and negative preview dimensions are preserved`() {
        val empty = Rect(10, 20, 10, 30)
        val source = Rect(0, 0, 100, 200)
        assertEquals(empty, cropFor(empty, 90, 100, 100))
        assertEquals(source, cropFor(source, 90, -100, 100))
    }

    @Test
    fun `matching preview aspect preserves source crop for every rotation`() {
        val source = Rect(10, 20, 1610, 920)

        assertEquals(source, cropFor(source, 0, 1600, 900))
        assertEquals(source, cropFor(source, 90, 900, 1600))
        assertEquals(source, cropFor(source, 180, 1600, 900))
        assertEquals(source, cropFor(source, 270, 900, 1600))
    }

    @Test
    fun `square preview crops horizontal source bounds for unrotated frame`() {
        assertEquals(
            Rect(350, 0, 1250, 900),
            cropFor(Rect(0, 0, 1600, 900), 0, 1000, 1000),
        )
    }

    @Test
    fun `resized preview preserves non-zero source origin`() {
        assertEquals(
            Rect(450, 200, 1350, 1100),
            cropFor(Rect(100, 200, 1700, 1100), 0, 1000, 1000),
        )
    }

    @Test
    fun `square preview crops vertical source bounds for unrotated frame`() {
        assertEquals(
            Rect(0, 350, 900, 1250),
            cropFor(Rect(0, 0, 900, 1600), 0, 1000, 1000),
        )
    }

    @Test
    fun `rotated frame maps preview vertical inset onto source horizontal axis`() {
        assertEquals(
            Rect(350, 0, 1250, 900),
            cropFor(Rect(0, 0, 1600, 900), 90, 1000, 1000),
        )
    }

    @Test
    fun `missing preview bounds preserve CameraX crop`() {
        val source = Rect(10, 20, 1610, 920)

        assertEquals(source, cropFor(source, 0, 0, 900))
        assertEquals(source, cropFor(source, 0, 1600, 0))
    }

    private fun cropFor(source: Rect, rotationDegrees: Int, previewWidth: Int, previewHeight: Int): Rect {
        val crop = mock(AndroidRect::class.java).apply {
            left = source.left
            top = source.top
            right = source.right
            bottom = source.bottom
        }
        val imageInfo = mock(ImageInfo::class.java)
        doReturn(rotationDegrees).`when`(imageInfo).rotationDegrees
        val image = mock(ImageProxy::class.java)
        doReturn(imageInfo).`when`(image).imageInfo
        doReturn(crop).`when`(image).cropRect
        doReturn(source.right).`when`(image).width
        doReturn(source.bottom).`when`(image).height
        return XCameraFrame(image, converter, previewWidth, previewHeight).use { it.cropRect }
    }
}
