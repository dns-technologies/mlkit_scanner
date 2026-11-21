package com.dns_technologies.mlkit_scanner.scanner

import android.util.Size

/**
 * Camera consumer with retained preview geometry.
 *
 * @property viewId Logical identity of this consumer.
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
