package com.dns_technologies.mlkit_scanner.scanner.components.camera.x

import com.dns_technologies.mlkit_scanner.PluginError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlin.math.abs

/** Tracks camera availability and retains applied controls across close and rebind events. */
internal class CameraRuntimeState {
    private val lock = Any()
    private val openWaiters = mutableSetOf<CompletableDeferred<Unit>>()
    private val zoom = RetainedControl<Float> { first, second ->
        abs(first - second) <= ZOOM_COMPARISON_EPSILON
    }
    private val torch = RetainedControl<Boolean> { first, second -> first == second }

    private var disposed = false
    private var cameraOpen = false
    private var cameraOpenRevision = 0L

    /**
     * Returns a deferred that completes when the current CameraX camera reaches the open state.
     *
     * The result is already completed when the camera is open and fails with
     * [PluginError.CameraSessionDisposed] after this state has been disposed.
     */
    fun awaitOpen(): Deferred<Unit> = synchronized(lock) {
        when {
            disposed -> failedDeferred(PluginError.CameraSessionDisposed)
            cameraOpen -> CompletableDeferred(Unit)
            else -> CompletableDeferred<Unit>().also { waiter ->
                openWaiters += waiter
                waiter.invokeOnCompletion {
                    synchronized(lock) { openWaiters -= waiter }
                }
            }
        }
    }

    /** Marks the camera open, completes pending [awaitOpen] calls, and reports a new opening. */
    fun onCameraOpened(): Boolean {
        val (opened, pending) = synchronized(lock) {
            if (disposed) return false
            val opened = !cameraOpen
            cameraOpen = true
            if (opened) {
                cameraOpenRevision += 1
                zoom.invalidateOperation()
                torch.invalidateOperation()
            }
            opened to openWaiters.toList().also { openWaiters.clear() }
        }
        pending.forEach { it.complete(Unit) }
        return opened
    }

    /**
     * Marks the camera unavailable and invalidates operations tied to its previous opening.
     *
     * When [error] is supplied, pending [awaitOpen] calls fail instead of waiting for a reopening.
     */
    fun onCameraUnavailable(error: Exception? = null) {
        val pending = synchronized(lock) {
            if (disposed) return
            cameraOpen = false
            zoom.invalidateOperation()
            torch.invalidateOperation()
            if (error == null) emptyList()
            else openWaiters.toList().also { openWaiters.clear() }
        }
        error?.let { failure ->
            pending.forEach { it.completeExceptionally(failure) }
        }
    }

    /** Starts a user zoom request, superseding any pending zoom restoration. */
    fun beginZoom(value: Float): Operation<Float> = synchronized(lock) {
        check(!disposed)
        zoom.beginUserOperation(value, cameraOpenRevision)
    }

    /** Starts zoom restoration when required for the currently open camera. */
    fun beginZoomRestoration(
        currentZoom: Float?,
        force: Boolean = false,
    ): Operation<Float>? = synchronized(lock) {
        if (disposed || !cameraOpen) null
        else zoom.beginRestoration(currentZoom, force, cameraOpenRevision)
    }

    /** Records zoom completion and reports whether the retained value must be restored. */
    fun completeZoom(operation: Operation<Float>, succeeded: Boolean): Completion =
        synchronized(lock) {
            if (disposed) Completion()
            else zoom.complete(operation, succeeded, cameraOpenRevision)
        }

    /** Starts an absolute user torch request, superseding pending torch restoration. */
    fun beginTorch(enabled: Boolean): Operation<Boolean> = synchronized(lock) {
        check(!disposed)
        torch.beginUserOperation(enabled, cameraOpenRevision)
    }

    /** Starts torch restoration when required for the currently open camera. */
    fun beginTorchRestoration(
        currentTorchEnabled: Boolean?,
        force: Boolean = false,
    ): Operation<Boolean>? = synchronized(lock) {
        if (disposed || !cameraOpen) null
        else torch.beginRestoration(currentTorchEnabled, force, cameraOpenRevision)
    }

    /** Records torch completion and reports whether the retained value must be restored. */
    fun completeTorch(operation: Operation<Boolean>, succeeded: Boolean): Completion =
        synchronized(lock) {
            if (disposed) Completion()
            else torch.complete(operation, succeeded, cameraOpenRevision)
        }

    /** Drops retained controls and fails current and future [awaitOpen] calls. */
    fun dispose() {
        val pending = synchronized(lock) {
            if (disposed) return
            disposed = true
            cameraOpen = false
            zoom.clear()
            torch.clear()
            openWaiters.toList().also { openWaiters.clear() }
        }
        pending.forEach { it.completeExceptionally(PluginError.CameraSessionDisposed) }
    }

    /** One CameraX control operation associated with a specific camera opening. */
    class Operation<T : Any> internal constructor(
        val value: T,
        internal val userInitiated: Boolean,
        internal val controlRevision: Long,
        internal val cameraOpenRevision: Long,
    )

    /** Follow-up policy produced when a CameraX operation completes. */
    data class Completion(
        val shouldRestore: Boolean = false,
        val restorationFailed: Boolean = false,
    )

    /** Creates an already-failed camera-control result. */
    private fun failedDeferred(error: Exception): Deferred<Unit> =
        CompletableDeferred<Unit>().also { it.completeExceptionally(error) }

    private class RetainedControl<T : Any>(
        private val valuesMatch: (T, T) -> Boolean,
    ) {
        var retainedValue: T? = null
            private set

        private var revision = 0L
        private var activeOperation: Operation<T>? = null

        /** Starts a new absolute user request and invalidates older control revisions. */
        fun beginUserOperation(value: T, cameraOpenRevision: Long): Operation<T> {
            revision += 1
            return Operation(
                value = value,
                userInitiated = true,
                controlRevision = revision,
                cameraOpenRevision = cameraOpenRevision,
            ).also { activeOperation = it }
        }

        /** Starts restoration when retained and observed values still differ. */
        fun beginRestoration(
            currentValue: T?,
            force: Boolean,
            cameraOpenRevision: Long,
        ): Operation<T>? {
            val value = retainedValue ?: return null
            if (
                activeOperation != null ||
                !force && currentValue != null && valuesMatch(currentValue, value)
            ) {
                return null
            }
            return Operation(
                value = value,
                userInitiated = false,
                controlRevision = revision,
                cameraOpenRevision = cameraOpenRevision,
            ).also { activeOperation = it }
        }

        /** Records completion and calculates whether another restoration is necessary. */
        fun complete(
            operation: Operation<T>,
            succeeded: Boolean,
            cameraOpenRevision: Long,
        ): Completion {
            val isLatestUserRequest =
                operation.userInitiated && operation.controlRevision == revision
            if (isLatestUserRequest && succeeded) retainedValue = operation.value
            val wasActive = activeOperation === operation
            if (wasActive) activeOperation = null
            return Completion(
                shouldRestore = isLatestUserRequest &&
                    (!succeeded || operation.cameraOpenRevision != cameraOpenRevision),
                restorationFailed = !operation.userInitiated && wasActive && !succeeded,
            )
        }

        /** Invalidates the operation associated with a previous camera opening. */
        fun invalidateOperation() {
            activeOperation = null
        }

        /** Drops the retained value and active operation. */
        fun clear() {
            retainedValue = null
            activeOperation = null
        }
    }

    private companion object {
        const val ZOOM_COMPARISON_EPSILON = 0.0001F
    }
}
