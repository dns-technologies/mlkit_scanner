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

/** Updates camera zoom ratio for the active scanner session. */
internal class SetZoomCommand(
    scannerSessionProvider: () -> ScannerSession?,
    commandScope: CoroutineScope,
) : AsyncScannerCommand(scannerSessionProvider, commandScope) {
    override suspend fun executeSuspendCommand(call: MethodCall, result: Result) {
        val arguments = call.arguments.requireMap()
        val viewId = arguments.requireInt(PluginConstants.viewIdArgument)
        val value = arguments.requireFiniteDouble(PluginConstants.valueArgument)
        if (viewId < 0 || value !in MIN_ZOOM..MAX_ZOOM) throw PluginError.InvalidArguments
        requireScannerSession().setZoom(viewId, value.toFloat())
        success(result)
    }

    private companion object {
        const val MIN_ZOOM = 0.0
        const val MAX_ZOOM = 1.0
    }
}
