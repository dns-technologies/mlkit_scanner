package com.dns_technologies.mlkit_scanner.scanner.components.camera.x

import com.dns_technologies.mlkit_scanner.scanner.components.camera.Rect
import kotlin.math.roundToInt

/** Maps the source viewport crop onto a resized fill-center preview without rebinding. */
internal object PreviewCropCalculator {
    /** Returns the centered source region currently visible inside the preview bounds. */
    fun calculate(
        source: Rect,
        rotationDegrees: Int,
        previewWidth: Int,
        previewHeight: Int,
    ): Rect {
        if (source.isEmpty || previewWidth <= 0 || previewHeight <= 0) return source

        val rotated = rotationDegrees == 90 || rotationDegrees == 270
        val orientedWidth = if (rotated) source.height.toDouble() else source.width.toDouble()
        val orientedHeight = if (rotated) source.width.toDouble() else source.height.toDouble()
        val sourceAspectRatio = orientedWidth / orientedHeight
        val previewAspectRatio = previewWidth.toDouble() / previewHeight.toDouble()
        val horizontalInset: Double
        val verticalInset: Double

        if (sourceAspectRatio > previewAspectRatio) {
            horizontalInset = (orientedWidth - orientedHeight * previewAspectRatio) / 2.0
            verticalInset = 0.0
        } else {
            horizontalInset = 0.0
            verticalInset = (orientedHeight - orientedWidth / previewAspectRatio) / 2.0
        }

        val sourceHorizontalInset = if (rotated) verticalInset else horizontalInset
        val sourceVerticalInset = if (rotated) horizontalInset else verticalInset
        val horizontalInsetPixels = sourceHorizontalInset.roundToInt()
        val verticalInsetPixels = sourceVerticalInset.roundToInt()
        return Rect(
            left = source.left + horizontalInsetPixels,
            top = source.top + verticalInsetPixels,
            right = source.right - horizontalInsetPixels,
            bottom = source.bottom - verticalInsetPixels,
        )
    }
}

/** Reuses a preview crop while every input that defines it remains unchanged. */
internal class PreviewCropState {
    private var input: Input? = null
    private var cropRect: Rect? = null

    /** Returns a cached preview crop until source or preview geometry changes. */
    fun resolve(
        source: Rect,
        rotationDegrees: Int,
        previewWidth: Int,
        previewHeight: Int,
    ): Rect {
        val nextInput = Input(source, rotationDegrees, previewWidth, previewHeight)
        if (input == nextInput) return requireNotNull(cropRect)

        return PreviewCropCalculator.calculate(
            source,
            rotationDegrees,
            previewWidth,
            previewHeight,
        ).also {
            input = nextInput
            cropRect = it
        }
    }

    private data class Input(
        val source: Rect,
        val rotationDegrees: Int,
        val previewWidth: Int,
        val previewHeight: Int,
    )
}
