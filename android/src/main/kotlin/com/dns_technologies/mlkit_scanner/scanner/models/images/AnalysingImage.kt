package com.dns_technologies.mlkit_scanner.scanner.models.images

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.util.Size

/**
 * Common image contract used by scanner components.
 *
 * @see [Orientation-Rotation](https://developer.android.com/training/camerax/orientation-rotation#image-rotation)
 * Documentation is provided for CameraX, but it is also relevant for other versions of the camera.
 */
interface AnalysingImage {
    /** Raw bytes of the image in the declared [format]. */
    val data: ByteArray

    /** Current image size. It can change after [crop]. */
    val size: Size

    /** Android image format. Must be one of the [ImageFormat] values. */
    val format: Int

    /** Rotation, in degrees, required to match the device orientation. */
    val rotationDegree: Int

    /** Current image width in pixels. */
    val width: Int
        get() = size.width

    /** Current image height in pixels. */
    val height: Int
        get() = size.height

    /**
     * Converting to [Bitmap]
     *
     * This method may not be used directly for the analyzer, but it is convenient
     * for checking the correctness of work with the image
     *
     * @return null if an image could not be converted to Bitmap
     */
    fun toBitmap(): Bitmap?

    /**
     * Crops an image using primitive pixel coordinates.
     *
     * The result of calling this method is a conversion of the values [data] and [size].
     * New [size] value must match the requested crop dimensions.
     * New [data] value must represent cropping image with the same [format]
     */
    fun crop(left: Int, top: Int, right: Int, bottom: Int)
}
