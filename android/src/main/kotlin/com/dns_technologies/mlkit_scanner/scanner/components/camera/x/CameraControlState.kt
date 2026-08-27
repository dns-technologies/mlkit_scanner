package com.dns_technologies.mlkit_scanner.scanner.components.camera.x

import kotlin.math.abs

/** Retains successfully applied CameraX controls across camera close and rebind events. */
internal class CameraControlState {
    private val lock = Any()
    private val zoom = RetainedControl<Float> { first, second ->
        abs(first - second) <= ZOOM_COMPARISON_EPSILON
    }
    private val torch = RetainedControl<Boolean> { first, second -> first == second }

    private var disposed = false
    private var cameraOpen = false
    private var cameraOpenRevision = 0L

    /** Marks the current camera unavailable and invalidates operations tied to that opening. */
    fun onCameraUnavailable() = synchronized(lock) {
        cameraOpen = false
        zoom.invalidateOperation()
        torch.invalidateOperation()
    }

    /** Returns true once for every transition to an open camera. */
    fun onCameraOpened(): Boolean = synchronized(lock) {
        if (disposed || cameraOpen) return@synchronized false
        cameraOpen = true
        cameraOpenRevision += 1
        zoom.invalidateOperation()
        torch.invalidateOperation()
        true
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

    /** Drops retained values and prevents stale completions from changing the state. */
    fun dispose() = synchronized(lock) {
        disposed = true
        cameraOpen = false
        zoom.clear()
        torch.clear()
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

    private class RetainedControl<T : Any>(
        private val valuesMatch: (T, T) -> Boolean,
    ) {
        var retainedValue: T? = null
            private set

        private var revision = 0L
        private var activeOperation: Operation<T>? = null

        fun beginUserOperation(value: T, cameraOpenRevision: Long): Operation<T> {
            revision += 1
            return Operation(
                value = value,
                userInitiated = true,
                controlRevision = revision,
                cameraOpenRevision = cameraOpenRevision,
            ).also { activeOperation = it }
        }

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

        fun invalidateOperation() {
            activeOperation = null
        }

        fun clear() {
            retainedValue = null
            activeOperation = null
        }
    }

    private companion object {
        const val ZOOM_COMPARISON_EPSILON = 0.0001F
    }
}
