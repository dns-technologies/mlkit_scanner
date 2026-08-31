package com.dns_technologies.mlkit_scanner.scanner.components.ui

import android.util.Log
import android.view.MotionEvent
import android.widget.FrameLayout
import com.dns_technologies.mlkit_scanner.scanner.Scanner
import com.dns_technologies.mlkit_scanner.scanner.components.ui.focus.FocusController
import com.dns_technologies.mlkit_scanner.scanner.components.ui.focus.FocusView
import com.dns_technologies.mlkit_scanner.scanner.components.ui.visor.VisorController
import com.dns_technologies.mlkit_scanner.scanner.models.RecognizeVisorCropRect

/** Coordinates scanner overlay UI and maps overlay interactions to scanner operations. */
internal class OverlayController(
    private val boundsView: FrameLayout,
    private val scanner: Scanner,
) {
    private val focusView = FocusView(boundsView.context)
    private val focusController = FocusController(focusView)
    private val visorController = VisorController(
        boundsView,
        focusView,
        focusController::updateCenter,
    )
    private var isDisposed = false
    private var focusBindingRevision = 0

    /** Connects focus UI callbacks to the active camera component. */
    fun bindFocus() {
        if (isDisposed) return
        addFocusView()
        focusController.bind(
            onAutoFocusRequest = { offsetX, offsetY ->
                requestFocus(AUTO_FOCUS_RESET_DELAY_MS, offsetX, offsetY)
            },
            onLockedFocusRequest = { offsetX, offsetY ->
                requestFocus(LOCKED_FOCUS_RESET_DELAY_MS, offsetX, offsetY)
            },
        )
    }

    /** Invalidates focus work started while this preview owned the camera. */
    fun unbindFocus() {
        if (isDisposed) return
        focusBindingRevision += 1
        focusController.unbind()
        focusView.resetIndicator()
    }

    /** Renders focus and visor UI for the scanner's current crop area. */
    fun renderCropArea(cropRect: RecognizeVisorCropRect) {
        if (isDisposed) return
        visorController.setCropArea(cropRect, scanner.isScanActive)
    }

    /** Redraws retained crop UI after the platform view render target is restored. */
    fun redrawCropArea() {
        if (isDisposed) return
        visorController.redraw()
    }

    /** Updates the visual active state of the current visor overlay. */
    fun setScanActive(isActive: Boolean) {
        if (isDisposed) return
        visorController.setScanActive(isActive)
    }

    /** Routes touch gestures to the focus overlay when it is attached. */
    fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (isDisposed) return false
        if (focusView.parent !== boundsView) return false

        if (event.action == MotionEvent.ACTION_UP) {
            boundsView.performClick()
        }
        focusView.onTouchEvent(event)
        return true
    }

    /** Releases every listener, callback, animation and child view owned by this overlay. */
    fun dispose() {
        if (isDisposed) return
        isDisposed = true
        focusController.dispose()
        focusView.dispose()
        visorController.dispose()
        if (focusView.parent === boundsView) boundsView.removeView(focusView)
    }

    /** Adds the focus overlay above scanner overlays. */
    private fun addFocusView() {
        if (focusView.parent === boundsView) return
        boundsView.addView(focusView)
    }

    /** Focus gestures have no Dart result channel, so failures are best-effort by contract. */
    private fun requestFocus(resetDelayMs: Long, offsetX: Float, offsetY: Float) {
        val bindingRevision = focusBindingRevision
        try {
            scanner.focusOnCenter(resetDelayMs, offsetX, offsetY).invokeOnCompletion { error ->
                if (
                    error != null &&
                    !isDisposed &&
                    bindingRevision == focusBindingRevision
                ) {
                    Log.w(TAG, "Camera focus request failed", error)
                }
            }
        } catch (error: Exception) {
            Log.w(TAG, "Camera focus request failed", error)
        }
    }

    private companion object {
        /** No reset delay is used while the focus point is locked. */
        const val LOCKED_FOCUS_RESET_DELAY_MS = 0L

        /** Default reset delay after a regular autofocus tap. */
        const val AUTO_FOCUS_RESET_DELAY_MS = 3000L

        const val TAG = "MlkitScannerOverlay"
    }
}
