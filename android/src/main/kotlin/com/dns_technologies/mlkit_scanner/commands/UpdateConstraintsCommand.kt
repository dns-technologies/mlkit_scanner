package com.dns_technologies.mlkit_scanner.commands

import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel.Result
import com.dns_technologies.mlkit_scanner.commands.base.ScannerCommand

/**
 * Preserves the Dart method contract while CameraX observes native preview layout changes itself.
 */
internal class UpdateConstraintsCommand : ScannerCommand({ null }) {
    override fun executeCommand(call: MethodCall, result: Result) {
        success(result)
    }
}
