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

/** One stateless command submitted to the active camera binding. */
sealed interface CameraCommand {
    /** Clears metering regions and restores continuous focus when supported. */
    data object ResetFocus : CameraCommand

    /** Focuses around a preview-relative point. */
    data class Focus(
        val resetDelayMs: Long,
        val offsetX: Float,
        val offsetY: Float,
    ) : CameraCommand

    /** Applies an absolute camera zoom ratio. */
    data class SetZoomRatio(val value: Float) : CameraCommand

    /** Applies an absolute torch state. */
    data class SetTorch(val enabled: Boolean) : CameraCommand
}

/**
 * Minimal adapter implemented by a concrete camera library integration.
 */
interface Camera {
    /** Native preview view supplied by the concrete camera implementation. */
    val previewView: View

    /** Binds frame analysis while keeping preview hidden until [showPreview] is called. */
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

    /** Executes exactly one command without retaining or restoring its desired value. */
    fun execute(command: CameraCommand): Deferred<Unit>

    /** Reveals preview after startup camera controls have been applied. */
    fun showPreview()

    /** Hides preview while another platform view's controls are being restored. */
    fun hidePreview()

    /** Removes the active CameraX use-case binding while keeping adapter resources reusable. */
    fun unbind()

    /** Releases every resource owned by the concrete camera implementation. */
    fun dispose()
}
