package com.dns_technologies.mlkit_scanner.scanner

import android.util.Size

/**
 * Registered camera consumer with retained viewport geometry.
 *
 * @property viewId Stable identifier assigned at registration.
 */
internal class ScannerConsumer(val viewId: Int) {
    /** Viewport size used for crop and focus coordinate mapping. */
    var size = Size(1, 1)
        private set

    /** Replaces the retained viewport dimensions. */
    fun updateGeometry(size: Size) {
        this.size = size
    }
}
