package com.dns_technologies.mlkit_scanner.commands.base

import com.dns_technologies.mlkit_scanner.PluginError
import com.dns_technologies.mlkit_scanner.scanner.Scanner
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel.Result
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** Base command abstraction for asynchronous Dart->native scanner commands. */
internal abstract class AsyncScannerCommand(
    scannerProvider: () -> Scanner?,
    private val commandScope: CoroutineScope,
) : BaseScannerCommand(scannerProvider) {
    /** Executes suspend command body with shared coroutine error handling. */
    fun execute(call: MethodCall, result: Result) {
        commandScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                currentCoroutineContext().ensureActive()
                executeSuspendCommand(call, result)
            } catch (error: CancellationException) {
                reportError(result, PluginError.CameraSessionDisposed)
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
