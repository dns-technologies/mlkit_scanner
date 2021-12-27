package com.dns_technologies.mlkit_scanner.analyzer

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.ImageFormat
import android.util.Size

/**
 * An abstract class representing an image for recognition
 *
 * [data] - byte array of the image
 * [size] - image size
 * [format] - image format. Must be initialized with one of the [ImageFormat] that android works with
 * [rotationDegree] - value, in degrees from 0 to 360, by which to rotate the image to match
 * the device orientation
 *
 * @see [Orientation-Rotation](https://developer.android.com/training/camerax/orientation-rotation#image-rotation)
 * Documentation is provided for CameraX, but it is also relevant for other versions of the camera.
 */
abstract class AnalysingImage(data: ByteArray, size: Size, val format: Int, val rotationDegree: Int) {
    var data: ByteArray
        protected set
    var size: Size
        protected set
    val width: Int
        get() = size.width
    val height: Int
        get() = size.height

    init {
        this.data = data
        this.size = size
    }

    /**
     * Converting to [Bitmap]
     *
     * This method may not be used directly for the analyzer, but it is convenient
     * for checking the correctness of work with the image
     *
     * @return null if an image could not be converted to Bitmap
     */
    abstract fun toBitmap(): Bitmap?

    /**
     * Cropping an image according to the [rect]
     *
     * [rect] must not extend beyond [size] of an image.
     * The result of calling this method is a conversion of the values [data] and [size].
     * New [size] value must match [Rect.width] and [Rect.height] parameters of the [rect].
     * New [data] value must represent cropping image with the same [format]
     */
    abstract fun cropToRect(rect: Rect)
}