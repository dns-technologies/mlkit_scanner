package com.dns_technologies.mlkit_scanner.scanner.components.camera

/** Availability of pixels belonging to the current preview output. */
enum class CameraPreviewState {
    /** The output has not confirmed a captured frame yet. */
    Starting,
    /** The output is delivering camera frames. */
    Streaming,
    /** The output retains a frame while camera delivery is suspended. */
    Paused,
}

/** Immutable texture identity, display coordinates and frame availability. */
data class CameraPreviewDescription(
    /** Texture identifier allocated by the rendering engine. */
    val textureId: Long,
    /** Buffer width after any transformations handled by the output producer. */
    val width: Int,
    /** Buffer height after any transformations handled by the output producer. */
    val height: Int,
    /** Clockwise rotation still required when displaying the buffer. */
    val rotationDegrees: Int,
    /** Whether the displayed buffer requires horizontal mirroring. */
    val mirrored: Boolean,
    /** Availability of a frame from the current output. */
    val state: CameraPreviewState,
    /** Immutable visible source region in buffer pixels before display rotation. */
    val cropRect: Rect,
)
