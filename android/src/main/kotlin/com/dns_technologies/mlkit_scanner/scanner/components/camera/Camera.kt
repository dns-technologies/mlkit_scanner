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

/**
 * Minimal adapter implemented by a concrete camera library integration.
 */
interface Camera {
    /** Native preview view supplied by the concrete camera implementation. */
    val previewView: View

    /** Starts frame analysis while keeping preview hidden until [showPreview] is called. */
    fun start(
        lifecycleOwner: LifecycleOwner,
        analysisExecutor: ExecutorService,
        onFrame: OnCameraFrame,
        onInit: OnInit,
        onError: OnError,
    )

    /** Returns true when the camera has an active binding. */
    fun isActive(): Boolean

    /** Returns whether the active camera exposes a flash unit usable as a torch. */
    fun isFlashSupported(): Boolean

    /** Applies the camera torch state and completes after the camera accepts the change. */
    fun setTorch(enabled: Boolean): Deferred<Unit>

    /** Starts focus and metering and completes after the camera accepts the action. */
    fun focusOnCenter(resetDelayMs: Long, offsetX: Float, offsetY: Float): Deferred<Unit>

    /** Applies normalized zoom and completes after the camera accepts the new value. */
    fun setZoom(value: Float): Deferred<Unit>

    /** Reveals preview after startup camera controls have been applied. */
    fun showPreview()

    /** Hides preview while another platform view's controls are being restored. */
    fun hidePreview()

    /** Releases resources owned by the concrete camera implementation. */
    fun dispose()
}
