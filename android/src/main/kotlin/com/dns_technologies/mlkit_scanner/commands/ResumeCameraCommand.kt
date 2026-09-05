package com.dns_technologies.mlkit_scanner.commands

import com.dns_technologies.mlkit_scanner.PluginConstants
import com.dns_technologies.mlkit_scanner.PluginError
import com.dns_technologies.mlkit_scanner.commands.base.ScannerCommand
import com.dns_technologies.mlkit_scanner.session.ScannerSession
import com.dns_technologies.mlkit_scanner.utils.requireInt
import com.dns_technologies.mlkit_scanner.utils.requireMap
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel.Result

/** Resumes camera work on active scanner session. */
internal class ResumeCameraCommand(
    scannerSessionProvider: () -> ScannerSession?,
) : ScannerCommand(scannerSessionProvider) {
    override fun executeCommand(call: MethodCall, result: Result) {
        val viewId = call.arguments.requireMap().requireInt(PluginConstants.viewIdArgument)
        if (viewId < 0) throw PluginError.InvalidArguments
        scannerSession()?.resumeCamera(viewId)
        success(result)
    }
}
