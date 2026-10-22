package com.dns_technologies.mlkit_scanner.commands.base

import com.dns_technologies.mlkit_scanner.PluginError
import com.dns_technologies.mlkit_scanner.scanner.Scanner
import io.flutter.plugin.common.MethodChannel.Result

/**
 * Shared command functionality that does not define sync or async execution policy.
 *
 * @property scannerProvider Provides the scanner available for command execution.
 */
internal sealed class BaseScannerCommand(private val scannerProvider: () -> Scanner?) {
    /** Sends a successful command completion. */
    protected fun success(result: Result) = result.success(true)

    /** Current scanner, or null when camera resources are unavailable. */
    protected fun scanner(): Scanner? = scannerProvider()

    /** Maps internal exceptions to typed plugin errors. */
    protected fun reportError(result: Result, error: Exception) {
        reportScannerError(result, error)
    }

    internal companion object {
        /** Completes a channel response with a stable error code and diagnostic details. */
        fun reportScannerError(result: Result, error: Exception) {
            if (error is PluginError) {
                result.error(error.errorCode, error.channelMessage, error.details)
            } else {
                result.error(
                    PluginError.UnknownError.errorCode,
                    error.message ?: PluginError.UnknownError.message,
                    mapOf("stacktrace" to error.stackTraceToString()),
                )
            }
        }
    }
}
