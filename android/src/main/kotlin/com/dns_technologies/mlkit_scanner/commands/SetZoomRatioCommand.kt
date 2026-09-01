package com.dns_technologies.mlkit_scanner.commands

import com.dns_technologies.mlkit_scanner.models.ScannerSession
import com.dns_technologies.mlkit_scanner.commands.base.AsyncScannerCommand
import com.dns_technologies.mlkit_scanner.commands.base.ScannerMethodArguments
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel.Result
import kotlinx.coroutines.CoroutineScope

/** Updates the absolute camera zoom ratio retained by one scanner view. */
internal class SetZoomRatioCommand(
    scannerSessionProvider: () -> ScannerSession?,
    commandScope: CoroutineScope,
) : AsyncScannerCommand(scannerSessionProvider, commandScope) {
    override suspend fun executeSuspendCommand(call: MethodCall, result: Result) {
        val arguments = ScannerMethodArguments.zoomRatio(call.arguments)
        scannerSessionProvider()?.setZoomRatio(arguments.viewId, arguments.value)
        success(result)
    }
}
