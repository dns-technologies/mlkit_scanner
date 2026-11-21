package com.dns_technologies.mlkit_scanner.scanner.components.camera

/** Immutable pixel rectangle with exclusive right and bottom bounds. */
data class Rect(
    /** Inclusive horizontal start in pixels. */
    val left: Int,
    /** Inclusive vertical start in pixels. */
    val top: Int,
    /** Exclusive horizontal end in pixels. */
    val right: Int,
    /** Exclusive vertical end in pixels. */
    val bottom: Int,
) {
    /** Rectangle width in pixels. */
    val width: Int = right - left

    /** Rectangle height in pixels. */
    val height: Int = bottom - top

    /** Returns true when this rectangle contains no positive-area pixels. */
    val isEmpty: Boolean = width <= 0 || height <= 0
}
