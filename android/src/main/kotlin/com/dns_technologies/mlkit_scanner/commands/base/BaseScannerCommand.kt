package com.dns_technologies.mlkit_scanner.commands.base

import com.dns_technologies.mlkit_scanner.PluginError
import com.dns_technologies.mlkit_scanner.session.ScannerSession
import io.flutter.plugin.common.MethodChannel.Result

/** Shared command functionality that does not define sync or async execution policy. */
internal sealed class BaseScannerCommand(
    private val scannerSessionProvider: () -> ScannerSession?,
) {
    /** Sends a successful command completion. */
    protected fun success(result: Result) = result.success(true)

    /** Returns the active scanner session when it exists. */
    protected fun scannerSession(): ScannerSession? = scannerSessionProvider()

    /** Sends a typed plugin error response. */
    protected fun reportError(
        result: Result,
        error: PluginError,
        details: Any? = error.details,
    ) {
        result.error(error.errorCode, error.message, details)
    }

    /** Maps internal exceptions to typed plugin errors. */
    protected fun reportError(result: Result, error: Exception) {
        if (error is PluginError) {
            reportError(result, error)
            return
        }

        reportError(
            result,
            PluginError.UnknownError,
            mapOf(
                "message" to (error.message ?: error::class.simpleName),
                "stackTrace" to error.stackTraceToString(),
            ),
        )
    }
}
