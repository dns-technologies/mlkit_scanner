package com.dns_technologies.mlkit_scanner.commands

import com.dns_technologies.mlkit_scanner.PluginConstants
import com.dns_technologies.mlkit_scanner.PluginError
import com.dns_technologies.mlkit_scanner.commands.base.AsyncScannerCommand
import com.dns_technologies.mlkit_scanner.session.ScannerSession
import com.dns_technologies.mlkit_scanner.utils.requireInt
import com.dns_technologies.mlkit_scanner.utils.requireMap
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel.Result
import kotlinx.coroutines.CoroutineScope

/** Toggles the scanner torch for the active session. */
internal class ToggleFlashCommand(
    scannerSessionProvider: () -> ScannerSession?,
    commandScope: CoroutineScope,
) : AsyncScannerCommand(scannerSessionProvider, commandScope) {
    override suspend fun executeSuspendCommand(
        call: MethodCall,
        result: Result,
    ) {
        val viewId = call.arguments.requireMap().requireInt(PluginConstants.viewIdArgument)
        if (viewId < 0) throw PluginError.InvalidArguments
        scannerSession()?.toggleFlashLight(viewId)
        success(result)
    }
}
