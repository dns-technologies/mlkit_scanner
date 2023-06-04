package com.dns_technologies.mlkit_scanner.analyzer.interfaces

import android.graphics.Rect
import android.util.Size
import com.google.mlkit.vision.common.InputImage

/** Abstract class of the image collector for recognition */
interface MlKitImageBuilder {
    /** Cropping an image according to the [rect] */
    fun cropToRect(rect: Rect)

    /** Converts to an image suitable for analysis by MlKit */
    fun buildMlKitImage(): InputImage

    /** Returns the size */
    fun getSize(): Size

    /** Returns rotation in degrees */
    fun getRotationDegrees(): Int
}