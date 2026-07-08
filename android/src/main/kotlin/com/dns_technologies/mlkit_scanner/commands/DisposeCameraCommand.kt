package com.dns_technologies.mlkit_scanner.commands

import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel.Result
import com.dns_technologies.mlkit_scanner.commands.base.ScannerCommand

/** Disposes scanner sessions and resets native state. */
internal class DisposeCameraCommand(
    private val onDispose: () -> Unit,
) : ScannerCommand({ null }) {
    override fun executeCommand(call: MethodCall, result: Result) {
        onDispose()
        success(result)
    }
}
