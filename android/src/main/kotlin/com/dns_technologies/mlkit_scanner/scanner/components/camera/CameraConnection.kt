package com.dns_technologies.mlkit_scanner.scanner.components.camera

import com.dns_technologies.mlkit_scanner.CameraControlOperation
import com.dns_technologies.mlkit_scanner.PluginError
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine

/** SDK binding and preview readiness. App lifecycle policy belongs to Dart and CameraX. */
internal class CameraConnection {
    private var binding: CompletableDeferred<Unit>? = null
    private var ready = CompletableDeferred<Unit>()
    private var disposed = false

    val isReady: Boolean get() = ready.isCompleted && !ready.isCancelled && !disposed

    /** The next capture must await a fresh binding after the old Activity is detached. */
    fun reset() {
        if (disposed) return
        binding?.cancel()
        binding = null
        ready.cancel()
        ready = CompletableDeferred()
    }

    /** Binding acknowledgment and OPEN can arrive in either order. */
    suspend fun awaitReady(bind: (OnInit) -> Unit) {
        val started = binding ?: CompletableDeferred<Unit>().also {
            binding = it
            bind { it.complete(Unit) }
        }
        awaitAll(started, ready)
    }

    fun onAvailabilityChanged(availability: CameraAvailability, viewId: Int?) {
        if (disposed) return
        if (availability == CameraAvailability.Open) {
            if (ready.isCancelled) ready = CompletableDeferred()
            ready.complete(Unit)
        } else {
            if (ready.isCompleted) ready = CompletableDeferred()
            val closed = availability as CameraAvailability.Closed
            if (closed.errorCode != null) ready.completeExceptionally(
                PluginError.CameraControlError(CameraControlOperation.AWAIT_OPEN,
                    viewId, closed.cause, closed.errorCode))
        }
    }

    /** Cancelling the caller also cancels the SDK completion it is awaiting. */
    suspend fun control(viewId: Int, kind: CameraControlOperation, call: () -> Deferred<Unit>) {
        val context = currentCoroutineContext()
        context.ensureActive()
        if (!isReady) throw PluginError.CameraControlError(kind, viewId)
        try {
            // Carry failures as data to preserve the original SDK exception across dispatchers.
            val failure = suspendCancellableCoroutine<Throwable?> { completion ->
                val pending = call()
                val listener = pending.invokeOnCompletion { completion.resume(it) }
                completion.invokeOnCancellation {
                    listener.dispose()
                    pending.cancel()
                }
            }
            failure?.let { throw it }
            context.ensureActive()
        } catch (error: Exception) {
            if (error is CancellationException && !context.isActive) throw error
            throw when (error) {
                is PluginError.CameraControlError -> error.contextualize(kind, viewId)
                is PluginError -> error
                else -> PluginError.CameraControlError(kind, viewId, error)
            }
        }
    }

    fun dispose(cause: Throwable) {
        if (disposed) return
        disposed = true
        binding?.completeExceptionally(cause)
        ready.completeExceptionally(cause)
    }
}
