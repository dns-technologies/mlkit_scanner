package com.dns_technologies.mlkit_scanner.scanner.components.camera

/** Immutable pixel rectangle with exclusive right and bottom bounds. */
data class Rect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int
        get() = right - left

    val height: Int
        get() = bottom - top
}
