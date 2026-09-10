package com.dns_technologies.mlkit_scanner.scanner.components.camera

import android.view.View
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.ExecutorService
import kotlinx.coroutines.Deferred

/** Callback invoked after camera initialization succeeds. */
typealias OnInit = () -> Unit

/** Callback invoked when camera initialization fails. */
typealias OnError = (e: Exception) -> Unit

/** Callback invoked with a frame scoped to the duration of the callback. */
typealias OnCameraFrame = (frame: CameraFrame) -> Unit

/** Callback invoked when the camera preview becomes ready or stops streaming. */
typealias OnCameraAvailabilityChanged = (availability: CameraAvailability) -> Unit

/** Device availability reported by the concrete camera adapter. */
sealed interface CameraAvailability {
    /** The camera is open and the preview has started delivering frames. */
    data object Open : CameraAvailability

    /** The device is not open; an optional CameraX state error explains the transition. */
    data class Closed(
        val errorCode: Int? = null,
        val cause: Throwable? = null,
    ) : CameraAvailability
}

/**
 * Minimal adapter implemented by a concrete camera library integration.
 *
 * Lifecycle and control calls run on the main thread. Controls act on the current binding and
 * return their asynchronous completion; desired settings and operation ordering belong to the
 * caller. Cancelling a returned result does not guarantee cancellation of hardware work.
 */
interface Camera {
    /** Native preview view supplied by the concrete camera implementation. */
    val previewView: View

    /** Binds preview and frame analysis; Flutter covers the view until capture completes. */
    fun bind(
        lifecycleOwner: LifecycleOwner,
        analysisExecutor: ExecutorService,
        onFrame: OnCameraFrame,
        onAvailabilityChanged: OnCameraAvailabilityChanged,
        onInit: OnInit,
        onError: OnError,
    )

    /** Returns true when use cases have an active lifecycle binding. */
    fun isBound(): Boolean

    /** Clears metering regions and restores continuous focus when supported. */
    fun resetFocus(): Deferred<Unit>

    /**
     * Focuses at [x], [y] in [previewView] pixels, measured from its top-left corner.
     * The caller chooses the point; a non-positive delay disables auto-reset.
     */
    fun focus(resetDelayMs: Long, x: Float, y: Float): Deferred<Unit>

    /** Applies an absolute zoom ratio within the current device's supported range. */
    fun setZoomRatio(ratio: Float): Deferred<Unit>

    /** Applies an absolute torch state; disabling an absent flash unit is a no-op. */
    fun setTorch(enabled: Boolean): Deferred<Unit>

    /** Removes the active CameraX use-case binding while keeping adapter resources reusable. */
    fun unbind()

    /** Releases every resource owned by the concrete camera implementation. */
    fun dispose()
}
