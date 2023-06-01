package com.dns_technologies.mlkit_scanner.models

import android.graphics.Rect
import android.graphics.ImageFormat
import android.util.Size

class Nv21ImageCropper {
    companion object {
        /**
         * [ImageFormat.NV21] image cropping algorithm
         *
         * [data] - original image byte array
         * [size] - original image size
         * [croppingRect] - a rectangle by which an original image will be cropped
         *
         * @see [a source the algorithm was find on](https://www.programmersought.com/article/75461140907/)
         * This page also presents an improved version of the algorithm, which,
         * according to the author, is 4 times faster than the current implementation.
         */
        fun crop(data: ByteArray, size: Size, croppingRect: Rect): ByteArray {
            val newWidth = croppingRect.width()
            val newHeight = croppingRect.height()
            val ySize = newHeight * newWidth
            val uvSize = ySize / 2
            val newData = ByteArray(ySize + uvSize)
            val verticalLength = croppingRect.top + newHeight
            val horizontalLength = croppingRect.left + newWidth
            for(i in croppingRect.top until verticalLength) {
                for (j in croppingRect.left until  horizontalLength) {
                    newData[(i - croppingRect.top) * newWidth + j - croppingRect.left] =
                        data[i * size.width + j]
                    newData[ySize + ((i - croppingRect.top) / 2) * newWidth + j - croppingRect.left] =
                        data[size.width * size.height + i / 2 * size.width + j]
                }
            }
            return newData
        }
    }
}