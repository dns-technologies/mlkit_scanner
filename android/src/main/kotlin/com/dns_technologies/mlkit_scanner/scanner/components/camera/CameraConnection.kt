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

/** Coordinates camera binding and preview readiness; lifecycle policy belongs to the caller. */
internal class CameraConnection {
    /** Acknowledgment for the current camera binding attempt. */
    private var binding: CompletableDeferred<Unit>? = null
    /** Readiness completion renewed when the camera closes or the binding resets. */
    private var ready = CompletableDeferred<Unit>()
    /** Terminal flag that prevents readiness from being revived by late callbacks. */
    private var disposed = false

    /** Whether the current camera readiness completed successfully before disposal. */
    val isReady: Boolean
        get() = ready.isCompleted && !ready.isCancelled && !disposed

    /** Invalidates the current binding so the next capture waits for fresh readiness. */
    fun reset() {
        if (disposed) return
        binding?.cancel()
        binding = null
        ready.cancel()
        ready = CompletableDeferred()
    }

    /** Waits for both binding acknowledgment and camera availability, in either order. */
    suspend fun awaitReady(bind: (OnInit) -> Unit) {
        val started =
            binding
                ?: CompletableDeferred<Unit>().also {
                    binding = it
                    bind { it.complete(Unit) }
                }
        awaitAll(started, ready)
    }

    /** Updates readiness or reports a camera opening failure for the selected widget. */
    fun onAvailabilityChanged(availability: CameraAvailability, viewId: Int?) {
        if (disposed) return
        if (availability == CameraAvailability.Open) {
            if (ready.isCancelled) ready = CompletableDeferred()
            ready.complete(Unit)
        } else {
            if (ready.isCompleted) ready = CompletableDeferred()
            val closed = availability as CameraAvailability.Closed
            if (closed.errorCode != null)
                ready.completeExceptionally(
                    PluginError.CameraControlError(
                        CameraControlOperation.AWAIT_OPEN,
                        viewId,
                        closed.cause,
                        closed.errorCode,
                    )
                )
        }
    }

    /** Cancelling the caller also cancels the pending camera completion it is awaiting. */
    suspend fun control(viewId: Int, kind: CameraControlOperation, call: () -> Deferred<Unit>) {
        val context = currentCoroutineContext()
        context.ensureActive()
        if (!isReady) throw PluginError.CameraControlError(kind, viewId)
        try {
            // Carry failures as data to preserve the original camera exception across dispatchers.
            val failure =
                suspendCancellableCoroutine<Throwable?> { completion ->
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

    /** Terminates pending binding and readiness waits with the supplied cause. */
    fun dispose(cause: Throwable) {
        if (disposed) return
        disposed = true
        binding?.completeExceptionally(cause)
        ready.completeExceptionally(cause)
    }
}
