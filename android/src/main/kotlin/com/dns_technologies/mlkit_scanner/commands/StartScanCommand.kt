package com.dns_technologies.mlkit_scanner.commands

import com.dns_technologies.mlkit_scanner.PluginConstants
import com.dns_technologies.mlkit_scanner.PluginError
import com.dns_technologies.mlkit_scanner.commands.base.ScannerCommand
import com.dns_technologies.mlkit_scanner.scanner.Scanner
import com.dns_technologies.mlkit_scanner.utils.requireMap
import com.dns_technologies.mlkit_scanner.utils.requireInt
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel.Result

/** Applies a point control to the scanner selected by Dart. */
internal class StartScanCommand(
    scannerProvider: () -> Scanner?,
) : ScannerCommand(scannerProvider) {
    override fun executeCommand(call: MethodCall, result: Result) {
        val arguments = call.arguments.requireMap()
        val type = arguments.requireInt("type")
        val delay = arguments.requireInt(PluginConstants.delayArgument)
        if (type != 0) throw PluginError.InvalidArguments
        scanner()?.startScan(delay)
        success(result)
    }
}
