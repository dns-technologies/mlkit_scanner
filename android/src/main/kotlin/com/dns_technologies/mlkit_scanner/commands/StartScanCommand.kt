package com.dns_technologies.mlkit_scanner.commands

import com.dns_technologies.mlkit_scanner.PluginConstants
import com.dns_technologies.mlkit_scanner.PluginError
import com.dns_technologies.mlkit_scanner.commands.base.ScannerCommand
import com.dns_technologies.mlkit_scanner.models.ScannerSession
import com.dns_technologies.mlkit_scanner.utils.requireInt
import com.dns_technologies.mlkit_scanner.utils.requireMap
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel.Result

/** Starts barcode analysis on the scanner session. */
internal class StartScanCommand(
    scannerSessionProvider: () -> ScannerSession?,
) : ScannerCommand(scannerSessionProvider) {
    override fun executeCommand(call: MethodCall, result: Result) {
        val arguments = call.arguments.requireMap()
        val recognitionType = arguments.requireInt(RECOGNITION_TYPE_ARGUMENT)
        val viewId = arguments.requireInt(PluginConstants.viewIdArgument)
        val periodMs = arguments.requireInt(PluginConstants.delayArgument)
        if (recognitionType != BARCODE_RECOGNITION_TYPE || viewId < 0 || periodMs < 0) {
            throw PluginError.InvalidArguments
        }
        requireScannerSession().startScan(viewId, periodMs)
        success(result)
    }

    private companion object {
        const val RECOGNITION_TYPE_ARGUMENT = "type"
        const val BARCODE_RECOGNITION_TYPE = 0
    }
}
