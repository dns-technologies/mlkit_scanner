package com.dns_technologies.mlkit_scanner.commands

import com.dns_technologies.mlkit_scanner.models.ScannerSession
import com.dns_technologies.mlkit_scanner.commands.base.AsyncScannerCommand
import com.dns_technologies.mlkit_scanner.commands.base.ScannerMethodArguments
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel.Result
import kotlinx.coroutines.CoroutineScope

/** Updates camera zoom ratio for the active scanner session. */
internal class SetZoomCommand(
    scannerSessionProvider: () -> ScannerSession?,
    commandScope: CoroutineScope,
) : AsyncScannerCommand(scannerSessionProvider, commandScope) {
    override suspend fun executeSuspendCommand(call: MethodCall, result: Result) {
        val arguments = ScannerMethodArguments.zoom(call.arguments)
        requireScannerSession().setZoom(arguments.viewId, arguments.value)
        success(result)
    }
}
