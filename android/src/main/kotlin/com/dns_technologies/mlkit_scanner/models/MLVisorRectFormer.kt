package com.dns_technologies.mlkit_scanner.models

import android.graphics.Rect

/**
 * Class forms [Rect] for cropping an image
 *
 * Rect are formed according to a ratio of the scanner widget size
 * to an app screen size ([widgetWidthScale] and [widgetHeightScale]) and rect of scanner
 * recognition area [recognizeCropRect]. When forming the rect, an orientation of the camera
 * and an orientation of the application screen are also taken into consideration.
 */
class MLVisorRectFormer(
        var recognizeCropRect: RecognizeVisorCropRect = RecognizeVisorCropRect(),
        var widgetWidthScale: Double = 1.0,
        var widgetHeightScale: Double = 1.0) {

    /** update ratio of the scanner widget size to the app screen size  */
    fun updateWidgetScales(widthScale: Double = widgetWidthScale, heightScale: Double = widgetHeightScale) {
        widgetWidthScale = widthScale
        widgetHeightScale = heightScale
    }

    /** Determines a need for additional preparation of the scanner camera recognition area */
    fun shouldCrop(): Boolean = recognizeCropRect.shouldCrop()
            || widgetWidthScale != 1.0
            || widgetHeightScale != 1.0

    /** Preparing a recognition rectangle */
    fun prepareRect(rect: Rect, cameraRotationDegrees: Int) {
        val width = rect.width()
        val height = rect.height()
        val resultScaleX = widgetWidthScale * recognizeCropRect.scaleWidth
        val resultScaleY = widgetHeightScale * recognizeCropRect.scaleHeight * 1.2
        val (widthCrop: Double, heightCrop: Double) = when(cameraRotationDegrees) {
            90, 270 -> Pair(resultScaleY, resultScaleX)
            else -> Pair(resultScaleX, resultScaleY)
        }
        val insetX = (width * (1 - widthCrop) / 2).toInt()
        val insetY = (height * (1 - heightCrop) / 2).toInt()
        val offsetX = calculateOffsetX(width, cameraRotationDegrees)
        val offsetY = calculateOffsetY(height, cameraRotationDegrees)
        rect.inset(insetX, insetY)
        rect.offset((offsetX * widgetHeightScale).toInt(), (offsetY * widgetWidthScale).toInt())
    }

    private fun calculateOffsetX(width: Int, rotation: Int): Int =
            when (rotation) {
                0 -> ((width / 2) * recognizeCropRect.centerOffsetX).toInt()
                90 -> ((width / 2) * recognizeCropRect.centerOffsetY).toInt()
                180 -> - ((width / 2) * recognizeCropRect.centerOffsetX).toInt()
                else -> - ((width / 2) * recognizeCropRect.centerOffsetY).toInt() // 270 rotation
            }

    private fun calculateOffsetY(height: Int, rotation: Int): Int =
            when (rotation) {
                0 -> (height / 2 * recognizeCropRect.centerOffsetY).toInt()
                90 -> - ((height / 2) * recognizeCropRect.centerOffsetX).toInt()
                180 -> - ((height / 2) * recognizeCropRect.centerOffsetY).toInt()
                else -> (height / 2 * recognizeCropRect.centerOffsetX).toInt() // 270 rotation
            }
}