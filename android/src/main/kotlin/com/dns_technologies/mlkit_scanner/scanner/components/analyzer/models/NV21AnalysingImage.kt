package com.dns_technologies.mlkit_scanner.scanner.components.analyzer.models

import android.graphics.*
import android.util.Size
import java.io.ByteArrayOutputStream
import java.lang.Exception

/**
 * [AnalysingImage] implementation representing an image of the [ImageFormat.NV21] format.
 * [Nv21ImageCropper] algorithm is using to crop the image
 */
class NV21AnalysingImage(data: ByteArray, size: Size, format: Int, rotationDegree: Int) :
    AnalysingImage(data, size, format, rotationDegree) {

    override fun toBitmap(): Bitmap? = bitmapFromBytes()

    override fun cropToRect(rect: Rect) {
        val newData = Nv21ImageCropper.crop(data, size, rect)
        if (newData != null) {
            data = newData
            size = Size(rect.width(), rect.height())
        }
    }

    /** Converts the current NV21 byte array into a [Bitmap] for ML Kit input preparation. */
    private fun bitmapFromBytes(): Bitmap? {
        val stream = ByteArrayOutputStream()
        return try {
            val image = YuvImage(data, ImageFormat.NV21, size.width, size.height, null)
            image.compressToJpeg(Rect(0, 0, size.width, size.height), JPEG_QUALITY, stream)
            BitmapFactory.decodeByteArray(stream.toByteArray(), 0, stream.size())
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } finally {
            stream.close()
        }
    }

    private companion object {
        const val JPEG_QUALITY = 80
    }
}
