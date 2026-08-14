package com.dns_technologies.mlkit_scanner.scanner.components.camera

/**
 * Camera-library-independent frame available only for the duration of its camera callback.
 *
 * Implementations expose NV21 data lazily and release camera-owned resources in [close]. Data
 * passed to [useNv21] must not escape its callback. A frame supports one access attempt.
 */
interface CameraFrame : AutoCloseable {
    val width: Int

    val height: Int

    val rotationDegree: Int

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
