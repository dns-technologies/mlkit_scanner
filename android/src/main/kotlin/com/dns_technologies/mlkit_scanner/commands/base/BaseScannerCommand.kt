package com.dns_technologies.mlkit_scanner.commands.base

import com.dns_technologies.mlkit_scanner.PluginError
import com.dns_technologies.mlkit_scanner.scanner.Scanner
import io.flutter.plugin.common.MethodChannel.Result

/**
 * Shared command functionality that does not define sync or async execution policy.
 *
 * @property scannerProvider Resolves the scanner while validating the current capture lease.
 */
internal sealed class BaseScannerCommand(private val scannerProvider: () -> Scanner?) {
    /** Sends a successful command completion. */
    protected fun success(result: Result) = result.success(true)

    /** Resolves the single SDK executor once, before any permission or device await. */
    protected fun scanner(): Scanner? = scannerProvider()

    /** Sends a typed plugin error response. */
    protected fun reportError(result: Result, error: PluginError, details: Any? = error.details) {
        result.error(error.errorCode, error.message, details)
    }

    /** Maps internal exceptions to typed plugin errors. */
    protected fun reportError(result: Result, error: Exception) {
        reportScannerError(result, error)
    }
}

/** Keeps direct plugin calls and command responses on the same Dart error contract. */
internal fun reportScannerError(result: Result, error: Exception) {
    val pluginError = error as? PluginError ?: PluginError.UnknownError
    val details =
        if (error is PluginError) error.details
        else
            mapOf(
                "message" to (error.message ?: error::class.simpleName),
                "stackTrace" to error.stackTraceToString(),
            )
    result.error(pluginError.errorCode, pluginError.message, details)
}
