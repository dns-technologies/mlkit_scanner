package com.dns_technologies.mlkit_scanner.scanner.utils

import com.dns_technologies.mlkit_scanner.scanner.components.camera.CameraFrame
import com.dns_technologies.mlkit_scanner.scanner.components.camera.Rect
import com.dns_technologies.mlkit_scanner.scanner.models.RecognizeVisorCropRect
import kotlin.math.ceil
import kotlin.math.floor

/** Calculates a valid YUV 4:2:0 crop rectangle from scanner visor geometry. */
internal object ScanAreaCalculator {
    /** Returns an even bounded crop, or an empty rectangle when no pixels can be analyzed. */
    fun calculate(
        frame: CameraFrame,
        scanArea: RecognizeVisorCropRect?,
    ): Rect = normalizeCropBounds(
        rect = scanArea?.let { calculateRawCropRect(frame, it) } ?: frame.cropRect.toRawCropRect(),
        bounds = frame.cropRect,
    )

    private fun calculateRawCropRect(
        frame: CameraFrame,
        scanArea: RecognizeVisorCropRect,
    ): RawCropRect {
        val bounds = frame.cropRect
        val centerX = (bounds.left + bounds.right) / 2.0
        val centerY = (bounds.top + bounds.bottom) / 2.0
        val rotated = frame.rotationDegree == 90 || frame.rotationDegree == 270
        val previewWidth = if (rotated) bounds.height else bounds.width
        val previewHeight = if (rotated) bounds.width else bounds.height
        val previewOffsetX = previewWidth * scanArea.centerOffsetX / 2.0
        val previewOffsetY = previewHeight * scanArea.centerOffsetY / 2.0
        val sourceHalfWidth = (
            if (rotated) bounds.width * scanArea.scaleHeight else bounds.width * scanArea.scaleWidth
        ) / 2.0
        val sourceHalfHeight = (
            if (rotated) bounds.height * scanArea.scaleWidth else bounds.height * scanArea.scaleHeight
        ) / 2.0
        val sourceCenterX = centerX + when (frame.rotationDegree) {
            90 -> previewOffsetY
            180 -> -previewOffsetX
            270 -> -previewOffsetY
            else -> previewOffsetX
        }
        val sourceCenterY = centerY + when (frame.rotationDegree) {
            90 -> -previewOffsetX
            180 -> -previewOffsetY
            270 -> previewOffsetX
            else -> previewOffsetY
        }
        return RawCropRect(
            left = sourceCenterX - sourceHalfWidth,
            top = sourceCenterY - sourceHalfHeight,
            right = sourceCenterX + sourceHalfWidth,
            bottom = sourceCenterY + sourceHalfHeight,
        )
    }

    private fun normalizeCropBounds(
        rect: RawCropRect,
        bounds: Rect,
    ): Rect {
        val rawLeft = minOf(rect.left, rect.right)
        val rawTop = minOf(rect.top, rect.bottom)
        val rawRight = maxOf(rect.left, rect.right)
        val rawBottom = maxOf(rect.top, rect.bottom)
        if (!rawLeft.isFinite() || !rawTop.isFinite() || !rawRight.isFinite() || !rawBottom.isFinite()) {
            return EMPTY_RECT
        }

        val boundsLeft = bounds.left.roundUpToEven()
        val boundsTop = bounds.top.roundUpToEven()
        val boundsRight = bounds.right.roundDownToEven()
        val boundsBottom = bounds.bottom.roundDownToEven()
        if (boundsRight - boundsLeft < MIN_CROP_SIZE || boundsBottom - boundsTop < MIN_CROP_SIZE) {
            return EMPTY_RECT
        }
        if (
            rawRight <= boundsLeft || rawBottom <= boundsTop ||
            rawLeft >= boundsRight || rawTop >= boundsBottom
        ) {
            return EMPTY_RECT
        }

        val left = ceil(rawLeft.coerceIn(boundsLeft.toDouble(), boundsRight.toDouble()))
            .toInt().roundUpToEven()
        val top = ceil(rawTop.coerceIn(boundsTop.toDouble(), boundsBottom.toDouble()))
            .toInt().roundUpToEven()
        val right = floor(rawRight.coerceIn(boundsLeft.toDouble(), boundsRight.toDouble()))
            .toInt().roundDownToEven()
        val bottom = floor(rawBottom.coerceIn(boundsTop.toDouble(), boundsBottom.toDouble()))
            .toInt().roundDownToEven()
        if (right - left < MIN_CROP_SIZE || bottom - top < MIN_CROP_SIZE) {
            return EMPTY_RECT
        }

        return Rect(
            left,
            top,
            right,
            bottom,
        )
    }

    private fun Rect.toRawCropRect(): RawCropRect = RawCropRect(
        left = left.toDouble(),
        top = top.toDouble(),
        right = right.toDouble(),
        bottom = bottom.toDouble(),
    )

    private fun Int.roundDownToEven(): Int = this and -2

    private fun Int.roundUpToEven(): Int = (this + 1) and -2

    private data class RawCropRect(
        val left: Double,
        val top: Double,
        val right: Double,
        val bottom: Double,
    )

    private const val MIN_CROP_SIZE = 2
    private val EMPTY_RECT = Rect(0, 0, 0, 0)
}
