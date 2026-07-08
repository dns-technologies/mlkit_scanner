package com.dns_technologies.mlkit_scanner.commands

import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel.Result
import com.dns_technologies.mlkit_scanner.commands.base.ScannerCommand

/** Toggles the scanner torch for the active session. */
internal class ToggleFlashCommand(
    scannerSessionProvider: () -> com.dns_technologies.mlkit_scanner.models.ScannerSession?,
) : ScannerCommand(scannerSessionProvider) {
    override fun executeCommand(
        call: MethodCall,
        result: Result,
    ) {
        scannerSessionProvider()?.toggleFlashLight()
        success(result)
    }
}
