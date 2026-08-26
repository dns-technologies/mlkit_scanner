package com.dns_technologies.mlkit_scanner.commands

import com.dns_technologies.mlkit_scanner.commands.base.ScannerCommand
import com.dns_technologies.mlkit_scanner.commands.base.ScannerMethodArguments
import com.dns_technologies.mlkit_scanner.models.ScannerSession
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel.Result

/** Cancels active barcode scanning. */
internal class CancelScanCommand(
    scannerSessionProvider: () -> ScannerSession?,
) : ScannerCommand(scannerSessionProvider) {
    override fun executeCommand(call: MethodCall, result: Result) {
        scannerSessionProvider()?.pauseScan(ScannerMethodArguments.viewId(call.arguments))
        success(result)
    }
}
