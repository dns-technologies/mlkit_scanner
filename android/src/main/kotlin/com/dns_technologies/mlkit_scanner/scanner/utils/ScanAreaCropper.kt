package com.dns_technologies.mlkit_scanner.scanner.utils

import android.graphics.Rect
import com.dns_technologies.mlkit_scanner.scanner.models.RecognizeVisorCropRect
import com.dns_technologies.mlkit_scanner.scanner.models.images.AnalysingImage

/** Applies configured scanner visor geometry to incoming analysis frames. */
internal object ScanAreaCropper {
    /** Crops the image using the configured scanner area and widget-to-display scale. */
    fun crop(
        image: AnalysingImage,
        scanArea: RecognizeVisorCropRect,
        scale: Pair<Double, Double>,
    ) {
        val cropRect = calculateCropRect(image, scanArea, scale)
        image.crop(cropRect.left, cropRect.top, cropRect.right, cropRect.bottom)
    }

    private fun calculateCropRect(
        image: AnalysingImage,
        scanArea: RecognizeVisorCropRect,
        scale: Pair<Double, Double>,
    ): Rect {
        val (widthScale, heightScale) = scale
        val (widthCrop, heightCrop) = calculateCropScale(image, scanArea, widthScale, heightScale)

        return Rect(0, 0, image.width, image.height).apply {
            inset(
                (image.width * (1 - widthCrop) / 2).toInt(),
                (image.height * (1 - heightCrop) / 2).toInt(),
            )
            offset(
                (calculateScanAreaOffsetX(image, scanArea) * heightScale).toInt(),
                (calculateScanAreaOffsetY(image, scanArea) * widthScale).toInt(),
            )
        }
    }

    private fun calculateCropScale(
        image: AnalysingImage,
        scanArea: RecognizeVisorCropRect,
        widthScale: Double,
        heightScale: Double,
    ): Pair<Double, Double> {
        val resultScaleX = widthScale * scanArea.scaleWidth
        val resultScaleY = heightScale * scanArea.scaleHeight * HEIGHT_COMPENSATION
        return when (image.rotationDegree) {
            90, 270 -> Pair(resultScaleY, resultScaleX)
            else -> Pair(resultScaleX, resultScaleY)
        }
    }

    private fun calculateScanAreaOffsetX(image: AnalysingImage, scanArea: RecognizeVisorCropRect): Int =
        when (image.rotationDegree) {
            0 -> ((image.width / 2) * scanArea.centerOffsetX).toInt()
            90 -> ((image.width / 2) * scanArea.centerOffsetY).toInt()
            180 -> -((image.width / 2) * scanArea.centerOffsetX).toInt()
            else -> -((image.width / 2) * scanArea.centerOffsetY).toInt()
        }

    private fun calculateScanAreaOffsetY(image: AnalysingImage, scanArea: RecognizeVisorCropRect): Int =
        when (image.rotationDegree) {
            0 -> (image.height / 2 * scanArea.centerOffsetY).toInt()
            90 -> -((image.height / 2) * scanArea.centerOffsetX).toInt()
            180 -> -((image.height / 2) * scanArea.centerOffsetY).toInt()
            else -> (image.height / 2 * scanArea.centerOffsetX).toInt()
        }

    /** Keeps parity with the existing visor-to-camera height mapping. */
    private const val HEIGHT_COMPENSATION = 1.2
}
