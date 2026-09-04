package com.dns_technologies.mlkit_scanner.commands

import com.dns_technologies.mlkit_scanner.PluginConstants
import com.dns_technologies.mlkit_scanner.PluginError
import com.dns_technologies.mlkit_scanner.commands.base.AsyncScannerCommand
import com.dns_technologies.mlkit_scanner.models.ScannerSession
import com.dns_technologies.mlkit_scanner.utils.requireFiniteDouble
import com.dns_technologies.mlkit_scanner.utils.requireInt
import com.dns_technologies.mlkit_scanner.utils.requireMap
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel.Result
import kotlinx.coroutines.CoroutineScope

/** Updates the absolute camera zoom ratio retained by one scanner view. */
internal class SetZoomRatioCommand(
    scannerSessionProvider: () -> ScannerSession?,
    commandScope: CoroutineScope,
) : AsyncScannerCommand(scannerSessionProvider, commandScope) {
    override suspend fun executeSuspendCommand(call: MethodCall, result: Result) {
        val arguments = call.arguments.requireMap()
        val viewId = arguments.requireInt(PluginConstants.viewIdArgument)
        val zoomRatio = arguments.requireFiniteDouble(PluginConstants.valueArgument).toFloat()
        if (viewId < 0 || !zoomRatio.isFinite() || zoomRatio <= 0.0F) {
            throw PluginError.InvalidArguments
        }
        scannerSession()?.setZoomRatio(viewId, zoomRatio)
        success(result)
    }
}
