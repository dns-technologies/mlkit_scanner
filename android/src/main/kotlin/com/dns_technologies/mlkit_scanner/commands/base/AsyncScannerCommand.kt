package com.dns_technologies.mlkit_scanner.commands.base

import com.dns_technologies.mlkit_scanner.models.ScannerSession
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel.Result
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Base command abstraction for asynchronous Dart->native scanner commands. */
internal abstract class AsyncScannerCommand(
    scannerSessionProvider: () -> ScannerSession?,
    private val commandScope: CoroutineScope,
) : BaseScannerCommand(scannerSessionProvider) {
    /** Executes suspend command body with shared coroutine error handling. */
    fun execute(call: MethodCall, result: Result) {
        commandScope.launch {
            try {
                executeSuspendCommand(call, result)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                reportError(result, error)
            }
        }
    }

    /** Suspend command-specific implementation. */
    protected abstract suspend fun executeSuspendCommand(
        call: MethodCall,
        result: Result,
    )
}
