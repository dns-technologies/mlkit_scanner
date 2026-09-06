package com.dns_technologies.mlkit_scanner.scanner

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.annotation.MainThread
import com.dns_technologies.mlkit_scanner.scanner.components.ui.OverlayController
import com.dns_technologies.mlkit_scanner.scanner.models.RecognizeVisorCropRect
import io.flutter.plugin.platform.PlatformView

/**
 * Android platform view that renders scanner preview and scanner overlays.
 *
 * @property preview Borrowed preview hosted by this container; its resources belong to the caller.
 * @property onDispose Notifies the caller when the platform view is disposed.
 */
@SuppressLint("ViewConstructor")
@MainThread
class ScannerView(
    context: Context,
    private val preview: View,
    onFocusRequest: (resetDelayMs: Long, offsetX: Float, offsetY: Float) -> Unit,
    onDispose: () -> Unit,
) : FrameLayout(context), PlatformView {
    private val overlayController = OverlayController(this, onFocusRequest)
    private var onDispose: (() -> Unit)? = onDispose
    private var previewReadyListener: ViewTreeObserver.OnPreDrawListener? = null
    private var previewReady = false
    private var isDisposed = false

    init {
        layoutParams = ViewGroup.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
    }

    /** Moves the shared preview here and reports when its current non-zero layout can be used. */
    fun attachPreview(onPreviewReady: () -> Unit) {
        if (isDisposed) return
        clearPreviewReadiness()
        val listener = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                // Android may already be iterating a snapshot containing a removed listener.
                if (isDisposed || previewReadyListener !== this || !hasPreview()) return true
                if (preview.width <= 0 || preview.height <= 0) return true
                clearPreviewReadiness()
                previewReady = true
                onPreviewReady()
                return true
            }
        }
        previewReadyListener = listener
        (preview.parent as? ViewGroup)?.removeView(preview)
        // Removing/adding a child may call external hierarchy listeners synchronously.
        if (previewReadyListener !== listener) return
        addView(preview, 0)
        if (previewReadyListener === listener) observePreviewReadiness()
    }

    /** Detaches the borrowed preview without releasing its resources. */
    fun detachPreview() {
        overlayController.setScanActive(false)
        overlayController.unbindFocus()
        clearPreviewReadiness()
        if (preview.parent === this) removeView(preview)
    }

    /** Returns whether this container currently hosts the one shared preview view. */
    fun hasPreview(): Boolean = preview.parent === this

    /** Returns whether the hosted preview has completed a non-zero layout in this container. */
    fun isPreviewReady(): Boolean = hasPreview() && previewReady

    /** Enables focus gestures and forwards requests through the supplied callback. */
    fun bindFocus() = overlayController.bindFocus()

    /** Stops focus gestures and resets their visual state. */
    fun unbindFocus() = overlayController.unbindFocus()

    /** Updates the scan overlay state in this preview container. */
    fun setScanActive(isActive: Boolean) = overlayController.setScanActive(isActive)

    /** Retains crop UI immediately; drawing follows this view's actual layout lifecycle. */
    fun setCropArea(cropRect: RecognizeVisorCropRect) {
        overlayController.setCropArea(cropRect)
    }

    /** Notifies the caller once, then releases local resources even if the callback throws. */
    override fun dispose() = dispose(notifyCaller = true)

    /** Releases local resources without invoking [onDispose]; repeated release is harmless. */
    fun release() = dispose(notifyCaller = false)

    private fun dispose(notifyCaller: Boolean) {
        if (isDisposed) return
        isDisposed = true
        val callback = onDispose
        onDispose = null
        try {
            if (notifyCaller) callback?.invoke()
        } finally {
            releaseViewResources()
        }
    }

    private fun releaseViewResources() {
        try {
            detachPreview()
        } finally {
            overlayController.dispose()
        }
    }

    // Observe this container, not the borrowed child's window attachment. Keep a pending wait
    // across temporary window detaches; one-shot listeners would silently discard it there.
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        observePreviewReadiness()
    }

    override fun onDetachedFromWindow() {
        previewReadyListener?.let(viewTreeObserver::removeOnPreDrawListener)
        super.onDetachedFromWindow()
    }

    private fun observePreviewReadiness() {
        previewReadyListener?.let { listener ->
            // A floating observer may have been merged into the window observer on attachment.
            viewTreeObserver.removeOnPreDrawListener(listener)
            viewTreeObserver.addOnPreDrawListener(listener)
        }
    }

    /** Cancels the pending layout callback and marks the preview as unavailable. */
    private fun clearPreviewReadiness() {
        previewReadyListener?.let(viewTreeObserver::removeOnPreDrawListener)
        previewReadyListener = null
        previewReady = false
    }

    /** Returns this native view to Flutter's platform view host. */
    override fun getView(): View = this

    /** Routes touch gestures to the focus overlay when it is attached. */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (!hasPreview()) return super.dispatchTouchEvent(ev)
        if (overlayController.dispatchTouchEvent(ev)) return true
        return super.dispatchTouchEvent(ev)
    }

    /** Confirms accessibility click handling for focus touch events. */
    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
