package com.dns_technologies.mlkit_scanner.commands

import com.dns_technologies.mlkit_scanner.models.ScannerSession
import com.dns_technologies.mlkit_scanner.commands.base.ScannerMethodArguments
import com.dns_technologies.mlkit_scanner.commands.base.ScannerCommand
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel.Result

/** Updates visor crop area for scan result area. */
internal class SetCropAreaCommand(
    scannerSessionProvider: () -> ScannerSession?,
) : ScannerCommand(scannerSessionProvider) {
    override fun executeCommand(call: MethodCall, result: Result) {
        val arguments = ScannerMethodArguments.cropRect(call.arguments)
        scannerSessionProvider()?.setCropArea(arguments.viewId, arguments.value)
        success(result)
    }
}
