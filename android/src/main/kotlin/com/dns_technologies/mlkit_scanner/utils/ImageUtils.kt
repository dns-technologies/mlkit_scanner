package com.dns_technologies.mlkit_scanner.utils

import android.graphics.*
import android.media.Image
import android.util.Size
import androidx.annotation.ColorInt
import java.io.ByteArrayOutputStream
import java.lang.Exception

object ImageUtils {
    private val CHANNEL_RANGE = 0 until (1 shl 18)

    fun convertYuv420888ImageToBitmap(image: Image): Bitmap {
        require(image.format == ImageFormat.YUV_420_888) {
            "Unsupported image format $(image.format)"
        }

        val planes = image.planes

        // Because of the variable row stride it's not possible to know in
        // advance the actual necessary dimensions of the yuv planes.
        val yuvBytes = planes.map { plane ->
            val buffer = plane.buffer
            val yuvBytes = ByteArray(buffer.capacity())
            buffer[yuvBytes]
            buffer.rewind()  // Be kind…
            yuvBytes
        }

        val yRowStride = planes[0].rowStride
        val uvRowStride = planes[1].rowStride
        val uvPixelStride = planes[1].pixelStride
        val width = image.width
        val height = image.height
        @ColorInt val argb8888 = IntArray(width * height)
        var i = 0
        for (y in 0 until height) {
            val pY = yRowStride * y
            val uvRowStart = uvRowStride * (y shr 1)
            for (x in 0 until width) {
                val uvOffset = (x shr 1) * uvPixelStride
                argb8888[i++] =
                        yuvToRgb(
                                yuvBytes[0][pY + x].toIntUnsigned(),
                                yuvBytes[1][uvRowStart + uvOffset].toIntUnsigned(),
                                yuvBytes[2][uvRowStart + uvOffset].toIntUnsigned()
                        )
            }
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(argb8888, 0, width, 0, 0, width, height)
        return bitmap
    }

    fun Image.toBitmap(): Bitmap {
        val yBuffer = planes[0].buffer // Y
        val uBuffer = planes[1].buffer // U
        val vBuffer = planes[2].buffer // V

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)

        //U and V are swapped
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, this.width, this.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, yuvImage.width, yuvImage.height), 100, out)
        val imageBytes = out.toByteArray()
        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
    }

    fun rotateAndCrop(
            bitmap: Bitmap,
            imageRotationDegrees: Int,
            cropRect: Rect
    ): Bitmap {
        val matrix = Matrix()
        matrix.preRotate(imageRotationDegrees.toFloat())
        return Bitmap.createBitmap(
                bitmap,
                cropRect.left,
                cropRect.top,
                cropRect.width(),
                cropRect.height(),
                matrix,
                true
        )
    }

    fun bitmapFromBytes(data: ByteArray, size: Size, format: Int): Bitmap? {
        var bitmap: Bitmap? = null
        val stream = ByteArrayOutputStream()
        try {
            val image = YuvImage(data, format, size.width, size.height, null)
            image.compressToJpeg(Rect(0, 0, size.width, size.height), 80, stream)
            bitmap = BitmapFactory.decodeByteArray(stream.toByteArray(), 0, stream.size())
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            stream.close()
        }
        return bitmap
    }

    @ColorInt
    private fun yuvToRgb(nY: Int, nU: Int, nV: Int): Int {
        var nY = nY
        var nU = nU
        var nV = nV
        nY -= 16
        nU -= 128
        nV -= 128
        nY = nY.coerceAtLeast(0)

        // This is the floating point equivalent. We do the conversion in integer
        // because some Android devices do not have floating point in hardware.
        // nR = (int)(1.164 * nY + 2.018 * nU);
        // nG = (int)(1.164 * nY - 0.813 * nV - 0.391 * nU);
        // nB = (int)(1.164 * nY + 1.596 * nV);
        var nR = 1192 * nY + 1634 * nV
        var nG = 1192 * nY - 833 * nV - 400 * nU
        var nB = 1192 * nY + 2066 * nU

        // Clamp the values before normalizing them to 8 bits.
        nR = nR.coerceIn(CHANNEL_RANGE) shr 10 and 0xff
        nG = nG.coerceIn(CHANNEL_RANGE) shr 10 and 0xff
        nB = nB.coerceIn(CHANNEL_RANGE) shr 10 and 0xff
        return -0x1000000 or (nR shl 16) or (nG shl 8) or nB
    }

    private fun Byte.toIntUnsigned(): Int {
        return toInt() and 0xFF
    }
}