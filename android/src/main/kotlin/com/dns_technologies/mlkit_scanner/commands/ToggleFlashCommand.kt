package com.dns_technologies.mlkit_scanner.commands

import com.dns_technologies.mlkit_scanner.PluginConstants
import com.dns_technologies.mlkit_scanner.PluginError
import com.dns_technologies.mlkit_scanner.commands.base.AsyncScannerCommand
import com.dns_technologies.mlkit_scanner.scanner.Scanner
import com.dns_technologies.mlkit_scanner.utils.requireMap
import com.dns_technologies.mlkit_scanner.utils.optionalBoolean
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel.Result
import kotlinx.coroutines.CoroutineScope

/** Applies a point control to the scanner selected by Dart. */
internal class ToggleFlashCommand(
    scannerProvider: () -> Scanner?,
    commandScope: CoroutineScope,
) : AsyncScannerCommand(scannerProvider, commandScope) {
    override suspend fun executeSuspendCommand(call: MethodCall, result: Result) {
        val enabled = call.arguments.requireMap().optionalBoolean(PluginConstants.valueArgument)
            ?: throw PluginError.InvalidArguments
        scanner()?.setTorch(enabled)
        success(result)
    }
}
