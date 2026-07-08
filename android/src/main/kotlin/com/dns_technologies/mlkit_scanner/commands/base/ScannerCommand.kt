package com.dns_technologies.mlkit_scanner.commands.base

import com.dns_technologies.mlkit_scanner.PluginError
import com.dns_technologies.mlkit_scanner.models.ScannerSession
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel.Result

/** Base command abstraction for handling a single Dart->native scanner command. */
internal abstract class ScannerCommand(
    protected val scannerSessionProvider: () -> ScannerSession?,
) {
    /** Executes command body with shared error handling. */
    fun execute(call: MethodCall, result: Result) {
        try {
            executeCommand(call, result)
        } catch (error: Throwable) {
            reportError(result, error)
        }
    }

    /** Command-specific implementation. */
    protected abstract fun executeCommand(
        call: MethodCall,
        result: Result,
    )

    /** Reads a session and validates it is active or returns an error. */
    protected fun activeScannerSession(result: Result, error: PluginError = PluginError.CameraIsNotInitialized): ScannerSession? {
        val session = scannerSessionProvider()
        if (session == null || !session.isActive()) {
            reportError(result, error)
            return null
        }
        return session
    }

    /** Sends a successful command completion. */
    protected fun success(result: Result) = result.success(true)

    /** Sends a typed plugin error response. */
    protected fun reportError(result: Result, error: PluginError, details: Any? = null) {
        result.error(error.errorCode, error.message, details)
    }

    /** Maps internal exceptions to typed plugin errors. */
    protected fun reportError(result: Result, error: Throwable) {
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
