package com.dns_technologies.mlkit_scanner.analyzer

import android.graphics.ImageFormat
import android.graphics.Rect
import android.media.Image
import android.util.Size
import com.dns_technologies.mlkit_scanner.analyzer.interfaces.MlKitImageBuilder
import com.google.mlkit.vision.common.InputImage

/** [MlKitImageBuilder] implementation representing an image of the [ImageFormat.YUV_420_888] format. */
class YUV420888MlKitImageBuilder(
    private val image: Image,
    private val size: Size,
    private val rotationDegree: Int
) : MlKitImageBuilder {
    override fun cropToRect(rect: Rect) {}

    override fun buildMlKitImage(): InputImage = InputImage.fromMediaImage(image, rotationDegree)

    override fun getSize(): Size = size

    override fun getRotationDegrees(): Int = rotationDegree
}