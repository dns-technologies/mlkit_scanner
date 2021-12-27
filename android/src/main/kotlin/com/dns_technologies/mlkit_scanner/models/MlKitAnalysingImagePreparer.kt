package com.dns_technologies.mlkit_scanner.models

import android.graphics.Rect
import com.dns_technologies.mlkit_scanner.analyzer.AnalysingImage
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
     * then additional preparation of the image is performed before converting [AnalysingImage] to [InputImage]:
     * preparing an image cropping [Rect], cropping an image.
     * If [image] is invalid, returns null. If parameters of the image for analysis
     * match parameters of the camera and [MLVisorRectFormer.shouldCrop] returns false,
     * the image will be converted to the format required for MlKit without additional preparation.
     */
    fun prepare(image: AnalysingImage): InputImage? {
        if (isImageValid(image)) {
            return if (visorRectFormer.shouldCrop()) {
                val analyzeCropRect = Rect(0, 0, image.width, image.height)
                visorRectFormer.prepareRect(analyzeCropRect, image.rotationDegree)
                image.cropToRect(analyzeCropRect)
                return image.toMlKitInputImage()
            } else {
                image.toMlKitInputImage()
            }
        }
        return null
    }

    private fun isImageValid(image: AnalysingImage?): Boolean = image != null
            && image.width != 0
            && image.height != 0

    private fun AnalysingImage.toMlKitInputImage() = InputImage.fromByteArray(
        this.data,
        this.width,
        this.height,
        this.rotationDegree,
        this.format
    )
}