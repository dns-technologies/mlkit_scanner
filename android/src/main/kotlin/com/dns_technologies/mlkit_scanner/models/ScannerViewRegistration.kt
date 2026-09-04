package com.dns_technologies.mlkit_scanner.models

import com.dns_technologies.mlkit_scanner.PluginConstants
import com.dns_technologies.mlkit_scanner.PluginError
import com.dns_technologies.mlkit_scanner.scanner.models.RecognizeVisorCropRect
import com.dns_technologies.mlkit_scanner.utils.optionalBoolean
import com.dns_technologies.mlkit_scanner.utils.optionalFiniteDouble
import com.dns_technologies.mlkit_scanner.utils.optionalMap
import com.dns_technologies.mlkit_scanner.utils.requireInt
import com.dns_technologies.mlkit_scanner.utils.requireMap

/** Initial configuration supplied while Flutter registers a scanner platform view. */
internal data class ScannerViewRegistration(
    val viewId: Int,
    val initialZoom: Double?,
    val initialCropRect: RecognizeVisorCropRect?,
    val initialFlashEnabled: Boolean?,
) {
    companion object {
        /** Creates a registration from StandardMessageCodec platform-view arguments. */
        fun from(arguments: Any?): ScannerViewRegistration {
            val map = arguments.requireMap()
            val viewId = map.requireInt(PluginConstants.viewIdArgument)
            val initialZoom = map.optionalFiniteDouble(PluginConstants.initialZoomArgument)
            if (viewId < 0 || initialZoom != null && initialZoom !in MIN_ZOOM..MAX_ZOOM) {
                throw PluginError.InvalidArguments
            }

            return ScannerViewRegistration(
                viewId = viewId,
                initialZoom = initialZoom,
                initialCropRect = map.optionalMap(PluginConstants.initialCropRectArgument)
                    ?.let { RecognizeVisorCropRect.fromMap(it) },
                initialFlashEnabled = map.optionalBoolean(
                    PluginConstants.initialFlashEnabledArgument,
                ),
            )
        }

        private const val MIN_ZOOM = 0.0
        private const val MAX_ZOOM = 1.0
    }
}
