package com.dns_technologies.mlkit_scanner.commands

import com.dns_technologies.mlkit_scanner.PluginConstants
import com.dns_technologies.mlkit_scanner.commands.base.AsyncScannerCommand
import com.dns_technologies.mlkit_scanner.scanner.Scanner
import com.dns_technologies.mlkit_scanner.utils.requireFiniteFloat
import com.dns_technologies.mlkit_scanner.utils.requireMap
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel.Result
import kotlinx.coroutines.CoroutineScope

/** Applies an absolute camera zoom ratio. */
internal class SetZoomRatioCommand(scannerProvider: () -> Scanner?, commandScope: CoroutineScope) :
    AsyncScannerCommand(scannerProvider, commandScope) {
    override suspend fun executeSuspendCommand(call: MethodCall, result: Result) {
        val value =
            call.arguments.requireMap().requireFiniteFloat(PluginConstants.valueArgument)
        scanner()?.setZoomRatio(value)
        success(result)
    }
}
