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

    /** Toggles the camera torch and completes after the camera accepts the change. */
    fun toggleFlashLight(): Deferred<Unit>

    /** Starts focus and metering and completes after the camera accepts the action. */
    fun focusOnCenter(resetDelayMs: Long, offsetX: Float, offsetY: Float): Deferred<Unit>

    /** Applies normalized zoom and completes after the camera accepts the new value. */
    fun setZoom(value: Float): Deferred<Unit>

    /** Reveals preview after startup camera controls have been applied. */
    fun showPreview()

    /** Releases resources owned by the concrete camera implementation. */
    fun dispose()
}
