package com.dns_technologies.mlkit_scanner.commands

import com.dns_technologies.mlkit_scanner.PluginConstants
import com.dns_technologies.mlkit_scanner.PluginError
import com.dns_technologies.mlkit_scanner.commands.base.ScannerCommand
import com.dns_technologies.mlkit_scanner.models.ScannerSession
import com.dns_technologies.mlkit_scanner.utils.requireInt
import com.dns_technologies.mlkit_scanner.utils.requireMap
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel.Result

/** Updates the cooldown applied after successful recognition. */
internal class SetScanDelayCommand(
    scannerSessionProvider: () -> ScannerSession?,
) : ScannerCommand(scannerSessionProvider) {
    override fun executeCommand(call: MethodCall, result: Result) {
        val arguments = call.arguments.requireMap()
        val viewId = arguments.requireInt(PluginConstants.viewIdArgument)
        val periodMs = arguments.requireInt(PluginConstants.delayArgument)
        if (viewId < 0 || periodMs < 0) throw PluginError.InvalidArguments
        scannerSession()?.updateScanPeriod(viewId, periodMs)
        success(result)
    }
}
