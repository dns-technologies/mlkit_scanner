package com.dns_technologies.mlkit_scanner.commands

import com.dns_technologies.mlkit_scanner.commands.base.ScannerCommand
import com.dns_technologies.mlkit_scanner.commands.base.ScannerMethodArguments
import com.dns_technologies.mlkit_scanner.models.ScannerSession
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel.Result

/** Resumes camera work on active scanner session. */
internal class ResumeCameraCommand(
    scannerSessionProvider: () -> ScannerSession?,
) : ScannerCommand(scannerSessionProvider) {
    override fun executeCommand(call: MethodCall, result: Result) {
        requireScannerSession().resumeCamera(ScannerMethodArguments.viewId(call.arguments))
        success(result)
    }
}
