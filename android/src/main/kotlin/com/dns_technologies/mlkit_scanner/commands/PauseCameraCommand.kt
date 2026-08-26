package com.dns_technologies.mlkit_scanner.commands

import com.dns_technologies.mlkit_scanner.commands.base.ScannerCommand
import com.dns_technologies.mlkit_scanner.commands.base.ScannerMethodArguments
import com.dns_technologies.mlkit_scanner.models.ScannerSession
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel.Result

/** Pauses camera work on active scanner session. */
internal class PauseCameraCommand(
    scannerSessionProvider: () -> ScannerSession?,
) : ScannerCommand(scannerSessionProvider) {
    override fun executeCommand(call: MethodCall, result: Result) {
        scannerSessionProvider()?.pauseCamera(ScannerMethodArguments.viewId(call.arguments))
        success(result)
    }
}
