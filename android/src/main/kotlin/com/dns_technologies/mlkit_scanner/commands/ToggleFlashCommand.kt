package com.dns_technologies.mlkit_scanner.commands

import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel.Result
import com.dns_technologies.mlkit_scanner.commands.base.AsyncScannerCommand
import com.dns_technologies.mlkit_scanner.commands.base.ScannerMethodArguments
import com.dns_technologies.mlkit_scanner.models.ScannerSession
import kotlinx.coroutines.CoroutineScope

/** Toggles the scanner torch for the active session. */
internal class ToggleFlashCommand(
    scannerSessionProvider: () -> ScannerSession?,
    commandScope: CoroutineScope,
) : AsyncScannerCommand(scannerSessionProvider, commandScope) {
    override suspend fun executeSuspendCommand(
        call: MethodCall,
        result: Result,
    ) {
        requireScannerSession().toggleFlashLight(ScannerMethodArguments.viewId(call.arguments))
        success(result)
    }
}
