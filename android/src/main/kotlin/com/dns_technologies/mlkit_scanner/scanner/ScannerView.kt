package com.dns_technologies.mlkit_scanner.scanner

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.view.OneShotPreDrawListener
import com.dns_technologies.mlkit_scanner.scanner.components.ui.OverlayController
import com.dns_technologies.mlkit_scanner.scanner.models.RecognizeVisorCropRect
import io.flutter.plugin.platform.PlatformView

/**
 * Android platform view that renders scanner preview and scanner overlays.
 *
 * @property scanner Shared scanner whose preview is hosted by this view.
 * @property onDispose Callback that unregisters this view from its scanner session.
 */
@SuppressLint("ViewConstructor")
class ScannerView(
    context: Context,
    private val scanner: Scanner,
    private val onDispose: () -> Unit,
) : FrameLayout(context), PlatformView {
    private val overlayController = OverlayController(this, scanner)
    private var previewReadyListener: OneShotPreDrawListener? = null
    private var previewReady = false
    private var isDisposed = false

    init {
        layoutParams = matchParentLayoutParams()
    }

    /** Moves the shared preview here and reports when its current non-zero layout can be used. */
    fun attachPreview(onPreviewReady: () -> Unit) {
        if (isDisposed) return
        val preview = scanner.previewView
        clearPreviewReadiness()
        (preview.parent as? ViewGroup)?.removeView(preview)
        addView(preview, 0)
        awaitPreviewReady(onPreviewReady)
    }

    /** Removes the shared preview without disposing the shared camera pipeline. */
    fun detachPreview() {
        overlayController.setScanActive(false)
        clearPreviewReadiness()
        val preview = scanner.previewView
        if (preview.parent === this) removeView(preview)
    }

    /** Returns whether this container currently hosts the one shared preview view. */
    fun hasPreview(): Boolean = scanner.previewView.parent === this

    /** Returns whether the hosted preview has completed a non-zero layout in this container. */
    fun isPreviewReady(): Boolean = hasPreview() && previewReady

    /** Connects focus UI to the shared camera after CameraX is ready. */
    fun bindFocus() = overlayController.bindFocus()

    /** Updates the scan overlay state in this preview container. */
    fun setScanActive(isActive: Boolean) = overlayController.setScanActive(isActive)

    /** Renders matching focus and visor UI for the scanner's current crop area. */
    fun renderCropArea(cropRect: RecognizeVisorCropRect) {
        overlayController.renderCropArea(cropRect)
    }

    /** Unregisters this platform view without releasing shared camera resources. */
    override fun dispose() {
        if (isDisposed) return
        isDisposed = true
        try {
            onDispose.invoke()
        } finally {
            disposeLocalView()
        }
    }

    /** Disposes the local view when the owning session is released. */
    fun disposeFromSession() {
        if (isDisposed) return
        isDisposed = true
        disposeLocalView()
    }

    private fun disposeLocalView() {
        detachPreview()
        overlayController.dispose()
    }

    private fun awaitPreviewReady(onPreviewReady: () -> Unit) {
        val preview = scanner.previewView
        previewReadyListener = OneShotPreDrawListener.add(preview) {
            previewReadyListener = null
            if (hasPreview()) {
                if (preview.width > 0 && preview.height > 0) {
                    previewReady = true
                    onPreviewReady()
                } else {
                    awaitPreviewReady(onPreviewReady)
                }
            }
        }
    }

    private fun clearPreviewReadiness() {
        previewReadyListener?.removeListener()
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

    /** Creates layout parameters that fill the platform view container. */
    private fun matchParentLayoutParams(): ViewGroup.LayoutParams = ViewGroup.LayoutParams(
        LayoutParams.MATCH_PARENT,
        LayoutParams.MATCH_PARENT,
    )
}
