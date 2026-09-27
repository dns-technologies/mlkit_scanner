package com.dns_technologies.mlkit_scanner.commands

import com.dns_technologies.mlkit_scanner.commands.base.AsyncScannerCommand
import com.dns_technologies.mlkit_scanner.scanner.Scanner
import com.dns_technologies.mlkit_scanner.scanner.models.RecognizeVisorCropRect
import com.dns_technologies.mlkit_scanner.utils.requireBoolean
import com.dns_technologies.mlkit_scanner.utils.requireFiniteFloat
import com.dns_technologies.mlkit_scanner.utils.requireMap
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel.Result
import kotlinx.coroutines.CoroutineScope

/** Decodes and applies a batch of camera settings to the current capture. */
internal class UpdateCameraSettingsCommand(scannerProvider: () -> Scanner?, commandScope: CoroutineScope) :
    AsyncScannerCommand(scannerProvider, commandScope) {
    override suspend fun executeSuspendCommand(call: MethodCall, result: Result) {
        val values = call.arguments.requireMap()
        val zoom = if (values.containsKey("zoomRatio")) values.requireFiniteFloat("zoomRatio") else null
        val torch = if (values.containsKey("torchEnabled")) values.requireBoolean("torchEnabled") else null
        val crop = if (values.containsKey("cropRect")) {
            RecognizeVisorCropRect.fromMap(values.requireMap("cropRect"))
        } else null

        if (crop != null) scanner()?.setCropArea(crop)
        if (zoom != null) scanner()?.setZoomRatio(zoom)
        // Resolve the scanner again because it may be unavailable after the awaited zoom operation.
        if (torch != null) scanner()?.setTorch(torch)
        success(result)
    }
}
