package com.dns_technologies.mlkit_scanner.scanner.components.camera.x

import com.dns_technologies.mlkit_scanner.CameraControlOperation
import com.dns_technologies.mlkit_scanner.PluginError
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred

/** Converts an asynchronous camera result while retaining operation and failure context. */
internal fun ListenableFuture<*>.asCameraControlDeferred(
    executor: Executor,
    operation: CameraControlOperation,
): Deferred<Unit> {
    val result = CompletableDeferred<Unit>()
    addListener(
        {
            try {
                get()
                result.complete(Unit)
            } catch (error: CancellationException) {
                result.completeExceptionally(
                    PluginError.CameraControlError(operation, cause = error),
                )
            } catch (error: ExecutionException) {
                result.completeExceptionally(
                    PluginError.CameraControlError(
                        operation,
                        cause = error.cause ?: error,
                    ),
                )
            } catch (error: Exception) {
                result.completeExceptionally(
                    PluginError.CameraControlError(operation, cause = error),
                )
            }
        },
        executor,
    )
    result.invokeOnCompletion {
        if (result.isCancelled) cancel(true)
    }
    return result
}
