package com.dns_technologies.mlkit_scanner.scanner

import android.util.Size
import com.dns_technologies.mlkit_scanner.PluginError
import com.dns_technologies.mlkit_scanner.utils.requireFiniteDouble
import com.dns_technologies.mlkit_scanner.utils.requireMap
import kotlin.math.roundToInt

/**
 * One registered Flutter widget, independently of the shared camera output.
 *
 * @property viewId Logical widget identifier supplied by Dart during registration.
 */
internal class ScannerConsumer(val viewId: Int) {
    /** Validated Flutter viewport size used for crop and focus coordinate mapping. */
    var size = Size(1, 1)
        private set

    /** Validates channel dimensions and stores a positive integer viewport size. */
    fun updateGeometry(arguments: Any?) {
        val values = arguments.requireMap()
        val width = values.requireFiniteDouble("width")
        val height = values.requireFiniteDouble("height")
        if (width <= 0 || height <= 0 || width > Int.MAX_VALUE || height > Int.MAX_VALUE)
            throw PluginError.InvalidArguments
        size = Size(width.roundToInt().coerceAtLeast(1), height.roundToInt().coerceAtLeast(1))
    }
}
