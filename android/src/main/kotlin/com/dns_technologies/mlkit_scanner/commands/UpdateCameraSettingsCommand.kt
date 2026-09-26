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

/** Applies a batch of changed controls validated by Dart to the current capture. */
internal class UpdateCameraSettingsCommand(scannerProvider: () -> Scanner?, commandScope: CoroutineScope) :
    AsyncScannerCommand(scannerProvider, commandScope) {
    /** Decodes all supplied fields before any side effect and awaits camera controls. */
    override suspend fun executeSuspendCommand(call: MethodCall, result: Result) {
        val values = call.arguments.requireMap()
        val zoom = if (values.containsKey("zoomRatio")) values.requireFiniteFloat("zoomRatio") else null
        val torch = if (values.containsKey("torchEnabled")) values.requireBoolean("torchEnabled") else null
        val crop = if (values.containsKey("cropRect")) {
            RecognizeVisorCropRect.fromMap(values.requireMap("cropRect"))
        } else null

        if (crop != null) scanner()?.setCropArea(crop)
        if (zoom != null) scanner()?.setZoomRatio(zoom)
        // Resolving again rejects a lease revoked while zoom was awaiting the SDK.
        if (torch != null) scanner()?.setTorch(torch)
        success(result)
    }
}
