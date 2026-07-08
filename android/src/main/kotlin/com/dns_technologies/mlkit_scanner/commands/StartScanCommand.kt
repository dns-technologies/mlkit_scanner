package com.dns_technologies.mlkit_scanner.commands

import com.dns_technologies.mlkit_scanner.scanner.models.AnalyzeOptions
import com.dns_technologies.mlkit_scanner.models.ScannerSession
import com.dns_technologies.mlkit_scanner.commands.base.ScannerCommand
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel.Result

/** Starts barcode analysis on the scanner session. */
internal class StartScanCommand(
    scannerSessionProvider: () -> ScannerSession?,
) : ScannerCommand(scannerSessionProvider) {
    @Suppress("UNCHECKED_CAST")
    override fun executeCommand(call: MethodCall, result: Result) {
        val options = AnalyzeOptions.fromMap(call.arguments as Map<String, Any?>)
        scannerSessionProvider()?.startScan(options.periodMs)
        success(result)
    }
}
