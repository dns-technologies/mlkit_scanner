package com.dns_technologies.mlkit_scanner.commands

import com.dns_technologies.mlkit_scanner.commands.base.ScannerCommand
import com.dns_technologies.mlkit_scanner.commands.base.ScannerMethodArguments
import com.dns_technologies.mlkit_scanner.models.ScannerSession
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel.Result

/** Starts barcode analysis on the scanner session. */
internal class StartScanCommand(
    scannerSessionProvider: () -> ScannerSession?,
) : ScannerCommand(scannerSessionProvider) {
    override fun executeCommand(call: MethodCall, result: Result) {
        val options = ScannerMethodArguments.scanOptions(call.arguments)
        scannerSessionProvider()?.startScan(options.viewId, options.periodMs)
        success(result)
    }
}
