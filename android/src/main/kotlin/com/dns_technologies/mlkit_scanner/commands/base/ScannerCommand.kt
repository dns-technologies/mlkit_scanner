package com.dns_technologies.mlkit_scanner.commands.base

import com.dns_technologies.mlkit_scanner.models.ScannerSession
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel.Result

/** Base command abstraction for handling a single Dart->native scanner command. */
internal abstract class ScannerCommand(
    scannerSessionProvider: () -> ScannerSession?,
) : BaseScannerCommand(scannerSessionProvider) {
    /** Executes command body with shared error handling. */
    fun execute(call: MethodCall, result: Result) {
        try {
            executeCommand(call, result)
        } catch (error: Exception) {
            reportError(result, error)
        }
    }

    /** Command-specific implementation. */
    protected abstract fun executeCommand(
        call: MethodCall,
        result: Result,
    )
}
