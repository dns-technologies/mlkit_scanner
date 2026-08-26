package com.dns_technologies.mlkit_scanner.scanner.components.camera

/**
 * Camera-library-independent frame available only for the duration of its camera callback.
 *
 * Implementations expose NV21 data lazily and release camera-owned resources in [close]. Data
 * passed to [useNv21] must not escape its callback. A frame supports one access attempt.
 */
interface CameraFrame : AutoCloseable {
    /** Width of the unrotated camera buffer in pixels. */
    val width: Int

    /** Height of the unrotated camera buffer in pixels. */
    val height: Int

    /** Clockwise rotation required to display the buffer upright. */
    val rotationDegree: Int

    /** Camera-library crop that corresponds to the field of view shown by preview. */
    val cropRect: Rect
        get() = Rect(0, 0, width, height)

    /** Provides a full or cropped NV21 buffer that remains valid only during [block]. */
    fun <T> useNv21(
        cropRect: Rect?,
        block: (
            bytes: ByteArray,
            width: Int,
            height: Int,
            rotationDegree: Int,
        ) -> T,
    ): T
}
