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
    ): Rect = calculate(frame.cropRect, frame.rotationDegree, scanArea)

    /** Resolves a crop inside explicit frame [bounds], primarily for cached state and tests. */
    internal fun calculate(
        bounds: Rect,
        rotationDegree: Int,
        scanArea: RecognizeVisorCropRect?,
    ): Rect = normalizeCropBounds(
        rect = scanArea?.let { calculateRawCropRect(bounds, rotationDegree, it) }
            ?: bounds.toRawCropRect(),
        bounds = bounds,
    )

    /** Maps normalized preview geometry into unbounded source-buffer coordinates. */
    private fun calculateRawCropRect(
        bounds: Rect,
        rotationDegree: Int,
        scanArea: RecognizeVisorCropRect,
    ): RawCropRect {
        val boundsWidth = bounds.width
        val boundsHeight = bounds.height
        val centerX = (bounds.left + bounds.right) / 2.0
        val centerY = (bounds.top + bounds.bottom) / 2.0
        val rotated = rotationDegree == 90 || rotationDegree == 270
        val previewWidth = if (rotated) boundsHeight else boundsWidth
        val previewHeight = if (rotated) boundsWidth else boundsHeight
        val previewOffsetX = previewWidth * scanArea.centerOffsetX / 2.0
        val previewOffsetY = previewHeight * scanArea.centerOffsetY / 2.0
        val sourceHalfWidth = (
            if (rotated) boundsWidth * scanArea.scaleHeight else boundsWidth * scanArea.scaleWidth
        ) / 2.0
        val sourceHalfHeight = (
            if (rotated) boundsHeight * scanArea.scaleWidth else boundsHeight * scanArea.scaleHeight
        ) / 2.0
        val sourceCenterX = centerX + when (rotationDegree) {
            90 -> previewOffsetY
            180 -> -previewOffsetX
            270 -> -previewOffsetY
            else -> previewOffsetX
        }
        val sourceCenterY = centerY + when (rotationDegree) {
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

    /** Intersects raw geometry with frame bounds and aligns it for YUV 4:2:0 sampling. */
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

    /** Converts integer frame bounds to intermediate floating-point geometry. */
    private fun Rect.toRawCropRect(): RawCropRect = RawCropRect(
        left = left.toDouble(),
        top = top.toDouble(),
        right = right.toDouble(),
        bottom = bottom.toDouble(),
    )

    /** Rounds this coordinate down to the nearest YUV chroma boundary. */
    private fun Int.roundDownToEven(): Int = this and -2

    /** Rounds this coordinate up to the nearest YUV chroma boundary. */
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

/** Reuses a scan crop while frame geometry and crop configuration remain unchanged. */
internal class ScanAreaState {
    private var input: Input? = null
    private var cropRect: Rect? = null

    /** Returns the cached crop unless frame geometry or crop configuration changed. */
    fun resolve(frame: CameraFrame, scanArea: RecognizeVisorCropRect?): Rect {
        val nextInput = Input(frame.cropRect, frame.rotationDegree, scanArea)
        if (input == nextInput) return requireNotNull(cropRect)

        return ScanAreaCalculator.calculate(
            nextInput.frameBounds,
            nextInput.rotationDegree,
            nextInput.scanArea,
        ).also {
            input = nextInput
            cropRect = it
        }
    }

    private data class Input(
        val frameBounds: Rect,
        val rotationDegree: Int,
        val scanArea: RecognizeVisorCropRect?,
    )
}
