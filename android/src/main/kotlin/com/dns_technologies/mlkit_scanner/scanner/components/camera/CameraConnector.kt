package com.dns_technologies.mlkit_scanner.scanner.components.camera

import android.util.Size
import androidx.annotation.MainThread
import androidx.lifecycle.LifecycleOwner
import com.dns_technologies.mlkit_scanner.CameraControlOperation
import com.dns_technologies.mlkit_scanner.PluginError
import java.util.concurrent.ExecutorService
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Owns a camera adapter, its binding readiness and contextualized asynchronous controls.
 *
 * Controls must be ordered by the caller; cancelling a control cancels its pending camera result.
 */
@MainThread
internal class CameraConnector(private val camera: Camera) {
    /** Acknowledgment for the current camera binding attempt. */
    private var binding: CompletableDeferred<Unit>? = null
    /** Readiness completion renewed when the camera closes or the binding resets. */
    private var ready = CompletableDeferred<Unit>()
    /** Terminal flag that prevents readiness from being revived by late callbacks. */
    private var disposed = false

    /** Whether the current camera readiness completed successfully before disposal. */
    val isReady: Boolean
        get() = ready.isCompleted && !ready.isCancelled && !disposed

    /** Reports preview metadata from the owned camera. */
    var onPreviewChanged: (CameraPreviewDescription?) -> Unit
        get() = camera.onPreviewChanged
        set(value) { camera.onPreviewChanged = value }

    /** Completes after the camera releases its preview resources. */
    val disposal: Deferred<Unit>
        get() = camera.disposal

    /** Updates the camera viewport used by preview and focus. */
    fun updateGeometry(size: Size) = camera.updateGeometry(size)

    /** Invalidates readiness and removes the binding while keeping the camera reusable. */
    fun unbind() {
        if (disposed) return
        binding?.cancel()
        binding = null
        ready.cancel()
        ready = CompletableDeferred()
        camera.unbind()
    }

    /**
     * Binds once and waits for both acknowledgment and availability, in either order.
     *
     * Binding callbacks use the borrowed main-thread [scope] until unbinding or disposal.
     * [onError] reports binding failures so the owner can release its dependent resources.
     */
    suspend fun awaitReady(
        lifecycleOwner: LifecycleOwner,
        analysisExecutor: () -> ExecutorService,
        onFrame: OnCameraFrame,
        scope: CoroutineScope,
        viewId: () -> Int?,
        onAvailabilityChanged: () -> Unit,
        onError: OnError,
    ) {
        check(!disposed) { "Camera connector is disposed" }
        val started = binding ?: CompletableDeferred<Unit>().also { attempt ->
            binding = attempt
            // Every callback belongs to one binding, including rebinds to the same Activity.
            fun post(action: () -> Unit) {
                scope.launch { if (!disposed && binding === attempt) action() }
            }
            try {
                camera.bind(
                    lifecycleOwner,
                    analysisExecutor(),
                    onFrame,
                    onAvailabilityChanged = { availability ->
                        post {
                            updateAvailability(availability, viewId())
                            onAvailabilityChanged()
                        }
                    },
                    onInit = { post { attempt.complete(Unit) } },
                    onError = { error -> post { onError(error) } },
                )
            } catch (error: Exception) {
                try {
                    onError(error)
                } catch (cleanup: Exception) {
                    if (cleanup !== error) error.addSuppressed(cleanup)
                }
                throw error
            }
        }
        awaitAll(started, ready)
    }

    /** Clears metering regions on the ready camera. */
    suspend fun resetFocus(viewId: Int) =
        control(viewId, CameraControlOperation.FOCUS, camera::resetFocus)

    /** Focuses at a point expressed in preview viewport pixels. */
    suspend fun focus(viewId: Int, resetDelayMs: Long, x: Float, y: Float) =
        control(viewId, CameraControlOperation.FOCUS) { camera.focus(resetDelayMs, x, y) }

    /** Applies an absolute zoom ratio to the ready camera. */
    suspend fun setZoomRatio(viewId: Int, ratio: Float) =
        control(viewId, CameraControlOperation.ZOOM) { camera.setZoomRatio(ratio) }

    /** Applies an absolute torch state to the ready camera. */
    suspend fun setTorch(viewId: Int, enabled: Boolean) =
        control(viewId, CameraControlOperation.TORCH) { camera.setTorch(enabled) }

    /** Updates readiness or reports an opening failure with the supplied consumer identifier. */
    private fun updateAvailability(availability: CameraAvailability, viewId: Int?) {
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
    private suspend fun control(viewId: Int, kind: CameraControlOperation, call: () -> Deferred<Unit>) {
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

    /** Terminates pending waits and releases the owned camera exactly once. */
    fun dispose(cause: Throwable) {
        if (disposed) return
        disposed = true
        binding?.completeExceptionally(cause)
        ready.completeExceptionally(cause)
        camera.dispose()
    }
}
