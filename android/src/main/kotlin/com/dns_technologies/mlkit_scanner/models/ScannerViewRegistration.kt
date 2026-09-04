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
    val initialZoomRatio: Double?,
    val initialCropRect: RecognizeVisorCropRect?,
    val initialFlashEnabled: Boolean?,
) {
    companion object {
        /** Creates a registration from StandardMessageCodec platform-view arguments. */
        fun from(arguments: Any?): ScannerViewRegistration {
            val map = arguments.requireMap()
            val viewId = map.requireInt(PluginConstants.viewIdArgument)
            val initialZoomRatio = map
                .optionalFiniteDouble(PluginConstants.initialZoomRatioArgument)
                ?.also(::requireValidZoomRatio)
            if (viewId < 0) throw PluginError.InvalidArguments

            return ScannerViewRegistration(
                viewId = viewId,
                initialZoomRatio = initialZoomRatio,
                initialCropRect = map.optionalMap(PluginConstants.initialCropRectArgument)
                    ?.let { RecognizeVisorCropRect.fromMap(it) },
                initialFlashEnabled = map.optionalBoolean(
                    PluginConstants.initialFlashEnabledArgument,
                ),
            )
        }

        private fun requireValidZoomRatio(value: Double) {
            val floatValue = value.toFloat()
            if (!floatValue.isFinite() || floatValue <= 0.0F) {
                throw PluginError.InvalidArguments
            }
        }
    }
}
