package com.dns_technologies.mlkit_scanner.scanner.utils

import com.dns_technologies.mlkit_scanner.scanner.components.camera.CameraFrame
import com.dns_technologies.mlkit_scanner.scanner.components.camera.Rect
import com.dns_technologies.mlkit_scanner.scanner.models.RecognizeVisorCropRect

/** Calculates a valid YUV 4:2:0 crop rectangle from scanner visor geometry. */
internal object ScanAreaCalculator {
    /** Returns an even, bounded crop rectangle while preserving existing visor mapping. */
    fun calculate(
        frame: CameraFrame,
        scanArea: RecognizeVisorCropRect,
        scale: Pair<Double, Double>,
    ): Rect {
        val rawCropRect = calculateRawCropRect(frame, scanArea, scale)
        return normalizeCropBounds(rawCropRect, frame.width, frame.height)
    }

    private fun calculateRawCropRect(
        frame: CameraFrame,
        scanArea: RecognizeVisorCropRect,
        scale: Pair<Double, Double>,
    ): Rect {
        val (widthScale, heightScale) = scale
        val (widthCrop, heightCrop) = calculateCropScale(
            frame.rotationDegree,
            scanArea,
            widthScale,
            heightScale,
        )

        val insetX = (frame.width * (1 - widthCrop) / 2).toInt()
        val insetY = (frame.height * (1 - heightCrop) / 2).toInt()
        val offsetX = (calculateOffsetX(frame, scanArea) * heightScale).toInt()
        val offsetY = (calculateOffsetY(frame, scanArea) * widthScale).toInt()
        return Rect(
            insetX + offsetX,
            insetY + offsetY,
            frame.width - insetX + offsetX,
            frame.height - insetY + offsetY,
        )
    }

    private fun calculateCropScale(
        rotationDegree: Int,
        scanArea: RecognizeVisorCropRect,
        widthScale: Double,
        heightScale: Double,
    ): Pair<Double, Double> {
        val resultScaleX = widthScale * scanArea.scaleWidth
        val resultScaleY = heightScale * scanArea.scaleHeight * HEIGHT_COMPENSATION
        return when (rotationDegree) {
            90, 270 -> Pair(resultScaleY, resultScaleX)
            else -> Pair(resultScaleX, resultScaleY)
        }
    }

    private fun calculateOffsetX(
        frame: CameraFrame,
        scanArea: RecognizeVisorCropRect,
    ): Int = when (frame.rotationDegree) {
        0 -> ((frame.width / 2) * scanArea.centerOffsetX).toInt()
        90 -> ((frame.width / 2) * scanArea.centerOffsetY).toInt()
        180 -> -((frame.width / 2) * scanArea.centerOffsetX).toInt()
        else -> -((frame.width / 2) * scanArea.centerOffsetY).toInt()
    }

    private fun calculateOffsetY(
        frame: CameraFrame,
        scanArea: RecognizeVisorCropRect,
    ): Int = when (frame.rotationDegree) {
        0 -> (frame.height / 2 * scanArea.centerOffsetY).toInt()
        90 -> -((frame.height / 2) * scanArea.centerOffsetX).toInt()
        180 -> -((frame.height / 2) * scanArea.centerOffsetY).toInt()
        else -> (frame.height / 2 * scanArea.centerOffsetX).toInt()
    }

    private fun normalizeCropBounds(
        rect: Rect,
        width: Int,
        height: Int,
    ): Rect {
        val imageWidth = width.roundDownToEven()
        val imageHeight = height.roundDownToEven()
        require(imageWidth >= MIN_CROP_SIZE && imageHeight >= MIN_CROP_SIZE)

        val left = minOf(rect.left, rect.right).roundDownToEven().coerceIn(0, imageWidth)
        val top = minOf(rect.top, rect.bottom).roundDownToEven().coerceIn(0, imageHeight)
        val right = maxOf(rect.left, rect.right).roundDownToEven().coerceIn(0, imageWidth)
        val bottom = maxOf(rect.top, rect.bottom).roundDownToEven().coerceIn(0, imageHeight)
        val cropLeft = left.coerceAtMost(imageWidth - MIN_CROP_SIZE)
        val cropTop = top.coerceAtMost(imageHeight - MIN_CROP_SIZE)

        return Rect(
            cropLeft,
            cropTop,
            right.coerceAtLeast(cropLeft + MIN_CROP_SIZE).coerceAtMost(imageWidth),
            bottom.coerceAtLeast(cropTop + MIN_CROP_SIZE).coerceAtMost(imageHeight),
        )
    }

    private fun Int.roundDownToEven(): Int = this - (this % 2)

    private const val HEIGHT_COMPENSATION = 1.2
    private const val MIN_CROP_SIZE = 2
}
