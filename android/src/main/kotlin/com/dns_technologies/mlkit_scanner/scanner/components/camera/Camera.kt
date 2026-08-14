package com.dns_technologies.mlkit_scanner.scanner.components.camera

import android.view.View
import androidx.lifecycle.LifecycleOwner
import com.dns_technologies.mlkit_scanner.scanner.models.images.AnalysingImage
import java.util.concurrent.ExecutorService

/** Callback invoked after camera initialization succeeds. */
typealias OnInit = () -> Unit

/** Callback invoked when camera initialization or processing fails. */
typealias OnError = (e: Exception) -> Unit

/** Lazily creates a scanner image from a camera frame. */
typealias CreateCameraFrame = () -> AnalysingImage

/** Callback invoked for each camera frame available for scanner analysis. */
typealias OnCameraFrame = (createFrame: CreateCameraFrame) -> Unit

/**
 * Minimal adapter implemented by a concrete camera library integration.
 */
interface Camera {
    /** Native preview view supplied by the concrete camera implementation. */
    val previewView: View

    /** Starts preview and frame analysis using the provided lifecycle and executor. */
    fun start(
        lifecycleOwner: LifecycleOwner,
        analysisExecutor: ExecutorService,
        onFrame: OnCameraFrame,
        onInit: OnInit,
        onError: OnError,
    )

    /** Returns true when the camera has an active binding. */
    fun isActive(): Boolean

    /** Toggles the camera torch when the active camera supports flash. */
    fun toggleFlashLight()

    /** Starts focus and metering around the visual scanner focus point. */
    fun focusOnCenter(resetDelayMs: Long, offsetX: Float, offsetY: Float)

    /** Applies normalized zoom to the active camera implementation. */
    fun setZoom(value: Float)

    /** Releases resources owned by the concrete camera implementation. */
    fun dispose()
}
