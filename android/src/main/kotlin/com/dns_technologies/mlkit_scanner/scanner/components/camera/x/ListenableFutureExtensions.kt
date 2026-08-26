package com.dns_technologies.mlkit_scanner.scanner.components.camera.x

import com.dns_technologies.mlkit_scanner.PluginError
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred

/** Converts a CameraX control future into a cancellable coroutine result with a stable error. */
internal fun ListenableFuture<*>.asCameraControlDeferred(executor: Executor): Deferred<Unit> {
    val result = CompletableDeferred<Unit>()
    addListener(
        {
            try {
                get()
                result.complete(Unit)
            } catch (_: CancellationException) {
                result.completeExceptionally(PluginError.CameraControlError)
            } catch (_: ExecutionException) {
                result.completeExceptionally(PluginError.CameraControlError)
            } catch (_: Exception) {
                result.completeExceptionally(PluginError.CameraControlError)
            }
        },
        executor,
    )
    result.invokeOnCompletion {
        if (result.isCancelled) cancel(true)
    }
    return result
}
