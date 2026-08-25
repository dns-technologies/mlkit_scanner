package com.dns_technologies.mlkit_scanner.scanner

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.dns_technologies.mlkit_scanner.scanner.components.ui.OverlayController
import com.dns_technologies.mlkit_scanner.scanner.models.RecognizeVisorCropRect
import io.flutter.plugin.platform.PlatformView

/** Android platform view that renders scanner preview and scanner overlays. */
@SuppressLint("ViewConstructor")
class ScannerView(
    context: Context,
    private val scanner: Scanner,
    private val onDispose: () -> Unit,
) : FrameLayout(context), PlatformView {
    private val overlayController = OverlayController(this, scanner)
    private var isDisposed = false

    init {
        layoutParams = matchParentLayoutParams()
        addOnLayoutChangeListener { _, l, t, r, b, oldL, oldT, oldR, oldB ->
            if (hasPreview() && (l != oldL || t != oldT || r != oldR || b != oldB)) {
                overlayController.updateScannerScale()
            }
        }
    }

    /** Moves the shared camera preview into this platform-view container. */
    fun attachPreview() {
        if (isDisposed) return
        val preview = scanner.previewView
        (preview.parent as? ViewGroup)?.removeView(preview)
        addView(preview, 0)
        overlayController.updateScannerScale()
    }

    /** Removes the shared preview without disposing the shared camera pipeline. */
    fun detachPreview() {
        overlayController.setScanActive(false)
        val preview = scanner.previewView
        if (preview.parent === this) removeView(preview)
    }

    /** Returns whether this container currently hosts the one shared preview view. */
    fun hasPreview(): Boolean = scanner.previewView.parent === this

    /** Connects focus UI to the shared camera after CameraX is ready. */
    fun bindFocus() = overlayController.bindFocus()

    /** Updates the scan overlay state in this preview container. */
    fun setScanActive(isActive: Boolean) = overlayController.setScanActive(isActive)

    /** Applies this container's scale to shared frame-crop calculations. */
    fun updateScannerScale() = overlayController.updateScannerScale()

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
            detachPreview()
        }
    }

    /** Disposes the local view when the owning session is released. */
    fun disposeFromSession() {
        if (isDisposed) return
        isDisposed = true
        detachPreview()
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
