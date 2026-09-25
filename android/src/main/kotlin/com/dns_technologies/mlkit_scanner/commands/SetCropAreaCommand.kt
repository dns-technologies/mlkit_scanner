package com.dns_technologies.mlkit_scanner.commands

import com.dns_technologies.mlkit_scanner.PluginConstants
import com.dns_technologies.mlkit_scanner.commands.base.ScannerCommand
import com.dns_technologies.mlkit_scanner.scanner.Scanner
import com.dns_technologies.mlkit_scanner.scanner.models.RecognizeVisorCropRect
import com.dns_technologies.mlkit_scanner.utils.requireMap
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel.Result

/** Applies a point control to the scanner selected by Dart. */
internal class SetCropAreaCommand(scannerProvider: () -> Scanner?) :
    ScannerCommand(scannerProvider) {
    /** Validates crop geometry and applies it to subsequent recognition frames. */
    override fun executeCommand(call: MethodCall, result: Result) {
        val crop =
            RecognizeVisorCropRect.fromMap(
                call.arguments.requireMap().requireMap(PluginConstants.cropRectArgument)
            )
        scanner()?.setCropArea(crop)
        success(result)
    }
}
