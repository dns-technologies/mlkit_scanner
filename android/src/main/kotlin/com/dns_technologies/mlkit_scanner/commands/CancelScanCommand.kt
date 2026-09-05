package com.dns_technologies.mlkit_scanner.commands

import com.dns_technologies.mlkit_scanner.PluginConstants
import com.dns_technologies.mlkit_scanner.PluginError
import com.dns_technologies.mlkit_scanner.commands.base.ScannerCommand
import com.dns_technologies.mlkit_scanner.session.ScannerSession
import com.dns_technologies.mlkit_scanner.utils.requireInt
import com.dns_technologies.mlkit_scanner.utils.requireMap
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel.Result

/** Cancels active barcode scanning. */
internal class CancelScanCommand(
    scannerSessionProvider: () -> ScannerSession?,
) : ScannerCommand(scannerSessionProvider) {
    override fun executeCommand(call: MethodCall, result: Result) {
        scannerSession()?.let { session ->
            val viewId = call.arguments.requireMap().requireInt(PluginConstants.viewIdArgument)
            if (viewId < 0) throw PluginError.InvalidArguments
            session.pauseScan(viewId)
        }
        success(result)
    }
}
