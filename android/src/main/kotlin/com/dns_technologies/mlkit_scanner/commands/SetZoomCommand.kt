package com.dns_technologies.mlkit_scanner.commands

import com.dns_technologies.mlkit_scanner.models.ScannerSession
import com.dns_technologies.mlkit_scanner.commands.base.ScannerCommand
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel.Result

/** Updates camera zoom ratio for the active scanner session. */
internal class SetZoomCommand(
    scannerSessionProvider: () -> ScannerSession?,
) : ScannerCommand(scannerSessionProvider) {
    override fun executeCommand(call: MethodCall, result: Result) {
        val value = call.arguments as Number
        scannerSessionProvider()?.setZoom(value.toFloat())
        success(result)
    }
}
