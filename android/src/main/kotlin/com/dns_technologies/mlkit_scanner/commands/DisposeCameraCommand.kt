package com.dns_technologies.mlkit_scanner.commands

import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel.Result
import com.dns_technologies.mlkit_scanner.commands.base.ScannerCommand
import com.dns_technologies.mlkit_scanner.commands.base.ScannerMethodArguments
import com.dns_technologies.mlkit_scanner.models.ScannerSession

/** Disposes scanner sessions and resets native state. */
internal class DisposeCameraCommand(
    scannerSessionProvider: () -> ScannerSession?,
) : ScannerCommand(scannerSessionProvider) {
    override fun executeCommand(call: MethodCall, result: Result) {
        val viewId = ScannerMethodArguments.viewId(call.arguments)
        scannerSessionProvider()?.disposeView(viewId)
        success(result)
    }
}
