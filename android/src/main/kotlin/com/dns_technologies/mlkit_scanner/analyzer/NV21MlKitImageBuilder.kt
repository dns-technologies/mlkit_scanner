package com.dns_technologies.mlkit_scanner.analyzer

import android.graphics.*
import android.util.Size
import com.dns_technologies.mlkit_scanner.analyzer.interfaces.MlKitImageBuilder
import com.google.mlkit.vision.common.InputImage

/** [MlKitImageBuilder] implementation representing an image of the [ImageFormat.NV21] format. */
class NV21MlKitImageBuilder(
    private var data: ByteArray,
    private var size: Size,
    private val rotationDegrees: Int
) :
    MlKitImageBuilder {

    override fun cropToRect(rect: Rect) {}

    override fun buildMlKitImage(): InputImage {
        return InputImage.fromByteArray(
            this.data,
            this.size.width,
            this.size.height,
            this.rotationDegrees,
            ImageFormat.NV21,
        )
    }

    override fun getSize(): Size = size

    override fun getRotationDegrees(): Int = rotationDegrees
}