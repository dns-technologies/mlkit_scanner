package com.dns_technologies.mlkit_scanner.models

import android.graphics.Rect
import com.dns_technologies.mlkit_scanner.analyzer.interfaces.MlKitImageBuilder
import com.google.mlkit.vision.common.InputImage

/**
 * Class for preparing an image obtained by the ScannerCamera implementation for
 * analysis by the MlKit library
 *
 * To form a rectangle of the scanned image for analysis, use [visorRectFormer]
 */
class MlKitAnalysingImagePreparer(var visorRectFormer: MLVisorRectFormer = MLVisorRectFormer()) {

    /**
     * Prepare an image for MlKit from an image obtained by the ScannerCamera implementation
     *
     * If required image dimensions differ from received from the camera image dimensions,
     * then additional preparation of the image is performed before converting [MlKitImageBuilder] to [InputImage]:
     * preparing an image cropping [Rect], cropping an image.
     * If [image] is invalid, returns null. If parameters of the image for analysis
     * match parameters of the camera and [MLVisorRectFormer.shouldCrop] returns false,
     * the image will be converted to the format required for MlKit without additional preparation.
     */
    fun prepare(image: MlKitImageBuilder): InputImage? {
        if (isImageInvalid(image)) {
            return null
        }
        if (visorRectFormer.shouldCrop()) {
            val imageSize = image.getSize()
            val analyzeCropRect = Rect(0, 0, imageSize.width, imageSize.height)
            visorRectFormer.prepareRect(analyzeCropRect, image.getRotationDegrees())
            image.cropToRect(analyzeCropRect)
        }
        return image.buildMlKitImage()
    }

    private fun isImageInvalid(image: MlKitImageBuilder): Boolean = with(image.getSize()) { width == 0 || height == 0 }
}