package com.dns_technologies.mlkit_scanner.scanner.components.camera

import com.google.mlkit.vision.common.InputImage

/**
 * Camera-library-independent frame available only for the duration of its camera callback.
 *
 * Implementations materialize an ML Kit image lazily and release camera-owned resources in
 * [close]. A frame supports one materialization attempt.
 */
interface CameraFrame : AutoCloseable {
    val width: Int

    val height: Int

    val rotationDegree: Int

    /** Creates a full-frame or cropped ML Kit image without changing frame ownership. */
    fun toInputImage(cropRect: Rect?): InputImage
}
