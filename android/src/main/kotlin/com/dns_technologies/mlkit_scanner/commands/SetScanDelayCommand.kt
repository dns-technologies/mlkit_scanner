package com.dns_technologies.mlkit_scanner.commands

import com.dns_technologies.mlkit_scanner.PluginConstants
import com.dns_technologies.mlkit_scanner.commands.base.ScannerCommand
import com.dns_technologies.mlkit_scanner.scanner.Scanner
import com.dns_technologies.mlkit_scanner.utils.requireInt
import com.dns_technologies.mlkit_scanner.utils.requireMap
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel.Result

/** Applies a point control to the scanner selected by Dart. */
internal class SetScanDelayCommand(scannerProvider: () -> Scanner?) :
    ScannerCommand(scannerProvider) {
    /** Parses and updates the cooldown after a successful recognition. */
    override fun executeCommand(call: MethodCall, result: Result) {
        val delay = call.arguments.requireMap().requireInt(PluginConstants.delayArgument)
        scanner()?.setScanPeriod(delay)
        success(result)
    }
}
