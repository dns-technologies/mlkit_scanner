package com.dns_technologies.mlkit_scanner.scanner.components.camera

import android.util.Size
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

    /**
     * The device is not open; an optional camera error explains the transition.
     *
     * @property errorCode Original state error code reported by the camera, when known.
     * @property cause Original camera failure retained for diagnostics.
     */
    data class Closed(val errorCode: Int? = null, val cause: Throwable? = null) :
        CameraAvailability
}

/**
 * Minimal adapter implemented by a concrete camera library integration.
 *
 * Lifecycle and control calls run on the main thread. Controls act on the current binding and
 * return their asynchronous completion; desired settings and operation ordering belong to the
 * caller. Cancelling a returned result does not guarantee cancellation of hardware work.
 */
interface Camera {
    /** Reports current preview metadata, or null when output is withdrawn. */
    var onPreviewChanged: (Map<String, Any>?) -> Unit

    /** Completes after disposal has released all camera and preview resources. */
    val disposal: Deferred<Unit>

    /** Updates the preview viewport used to map recognition crops and focus coordinates. */
    fun updateGeometry(size: Size)

    /** Binds preview and frame analysis to the supplied lifecycle and callbacks. */
    fun bind(
        lifecycleOwner: LifecycleOwner,
        analysisExecutor: ExecutorService,
        onFrame: OnCameraFrame,
        onAvailabilityChanged: OnCameraAvailabilityChanged,
        onInit: OnInit,
        onError: OnError,
    )

    /** Returns true when the camera has an active lifecycle binding. */
    fun isBound(): Boolean

    /** Clears metering regions and restores continuous focus when supported. */
    fun resetFocus(): Deferred<Unit>

    /**
     * Focuses at [x], [y] in preview viewport pixels, measured from its top-left corner. The caller
     * chooses the point; a non-positive delay disables auto-reset.
     */
    fun focus(resetDelayMs: Long, x: Float, y: Float): Deferred<Unit>

    /** Applies an absolute zoom ratio within the current device's supported range. */
    fun setZoomRatio(ratio: Float): Deferred<Unit>

    /** Applies an absolute torch state; disabling an absent flash unit is a no-op. */
    fun setTorch(enabled: Boolean): Deferred<Unit>

    /** Removes the active lifecycle binding while keeping camera resources reusable. */
    fun unbind()

    /** Releases every resource owned by the concrete camera implementation. */
    fun dispose()
}
