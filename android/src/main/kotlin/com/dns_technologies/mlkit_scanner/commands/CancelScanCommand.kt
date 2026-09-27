package com.dns_technologies.mlkit_scanner.commands

import com.dns_technologies.mlkit_scanner.commands.base.ScannerCommand
import com.dns_technologies.mlkit_scanner.scanner.Scanner
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel.Result

/** Cancels recognition and revokes result delivery. */
internal class CancelScanCommand(scannerProvider: () -> Scanner?) :
    ScannerCommand(scannerProvider) {
    override fun executeCommand(call: MethodCall, result: Result) {
        scanner()?.pauseScan()
        success(result)
    }
}
