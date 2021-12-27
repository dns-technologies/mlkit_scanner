package com.dns_technologies.mlkit_scanner.analyzer

import android.graphics.*
import android.util.Size
import com.dns_technologies.mlkit_scanner.models.Nv21ImageCropper
import com.dns_technologies.mlkit_scanner.utils.ImageUtils

/**
 * [AnalysingImage] implementation representing an image of the [ImageFormat.NV21] format.
 * [Nv21ImageCropper] algorithm is using to crop the image
 */
class NV21AnalysingImage(data: ByteArray, size: Size, format: Int, rotationDegree: Int) :
    AnalysingImage(data, size, format, rotationDegree) {

    override fun toBitmap(): Bitmap? = ImageUtils.bitmapFromBytes(data, size, ImageFormat.NV21)

    override fun cropToRect(rect: Rect) {
        val newData = Nv21ImageCropper.crop(data, size, rect)
        if (newData != null) {
            data = newData
            size = Size(rect.width(), rect.height())
        }
    }
}