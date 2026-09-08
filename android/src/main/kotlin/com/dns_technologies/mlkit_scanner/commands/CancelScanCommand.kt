package com.dns_technologies.mlkit_scanner.commands

import com.dns_technologies.mlkit_scanner.commands.base.ScannerCommand
import com.dns_technologies.mlkit_scanner.scanner.Scanner
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel.Result

/** Applies a point control to the scanner selected by Dart. */
internal class CancelScanCommand(
    scannerProvider: () -> Scanner?,
) : ScannerCommand(scannerProvider) {
    override fun executeCommand(call: MethodCall, result: Result) {
        scanner()?.pauseScan()
        success(result)
    }
}
