package com.dns_technologies.mlkit_scanner.commands

import com.dns_technologies.mlkit_scanner.scanner.models.RecognizeVisorCropRect
import com.dns_technologies.mlkit_scanner.models.ScannerSession
import com.dns_technologies.mlkit_scanner.commands.base.ScannerCommand
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel.Result

/** Updates visor crop area for scan result area. */
internal class SetCropAreaCommand(
    scannerSessionProvider: () -> ScannerSession?,
) : ScannerCommand(scannerSessionProvider) {
    @Suppress("UNCHECKED_CAST")
    override fun executeCommand(call: MethodCall, result: Result) {
        val cropRect = RecognizeVisorCropRect.fromMap(call.arguments as Map<String, Any?>)
        scannerSessionProvider()?.setCropArea(cropRect)
        success(result)
    }
}
