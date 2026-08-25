package com.dns_technologies.mlkit_scanner.scanner.components.ui

import android.graphics.Point
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
    private val focusController = FocusController(boundsView, focusView)
    private val visorController = VisorController(boundsView, focusView)

    /** Connects focus UI callbacks to the active camera component. */
    fun bindFocus() {
        addFocusView()
        focusController.bind(
            onAutoFocusRequest = { offsetX, offsetY ->
                scanner.focusOnCenter(AUTO_FOCUS_RESET_DELAY_MS, offsetX, offsetY)
            },
            onLockedFocusRequest = { offsetX, offsetY ->
                scanner.focusOnCenter(LOCKED_FOCUS_RESET_DELAY_MS, offsetX, offsetY)
            },
        )
    }

    /** Renders focus and visor UI for the scanner's current crop area. */
    fun renderCropArea(cropRect: RecognizeVisorCropRect) {
        updateScannerScale()
        focusController.updateCenter(cropRect.centerOffsetX.toFloat(), cropRect.centerOffsetY.toFloat())
        visorController.setCropArea(cropRect, scanner.isScanActive)
    }

    /** Updates the visual active state of the current visor overlay. */
    fun setScanActive(isActive: Boolean) {
        visorController.setScanActive(isActive)
    }

    /** Routes touch gestures to the focus overlay when it is attached. */
    fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (focusView.parent !== boundsView) return false

        if (event.action == MotionEvent.ACTION_UP) {
            boundsView.performClick()
        }
        focusView.onTouchEvent(event)
        return true
    }

    /** Sends the current view-to-display scale to the scanner. */
    fun updateScannerScale() {
        val (widthScale, heightScale) = calculateScale()
        scanner.setScale(widthScale, heightScale)
    }

    /** Adds the focus overlay above scanner overlays. */
    private fun addFocusView() {
        if (focusView.parent === boundsView) return
        boundsView.addView(focusView)
    }

    /** Calculates the ratio between the scanner view size and the display size. */
    private fun calculateScale(): Pair<Double, Double> {
        val screenSize = getDisplaySize()
        if (screenSize.x == 0 || screenSize.y == 0) return Pair(1.0, 1.0)
        return Pair(
            boundsView.measuredWidth.toDouble() / screenSize.x,
            boundsView.measuredHeight.toDouble() / screenSize.y,
        )
    }

    /** Reads the current display size used for scale calculations. */
    private fun getDisplaySize(): Point {
        val displayMetrics = boundsView.resources.displayMetrics
        return Point(displayMetrics.widthPixels, displayMetrics.heightPixels)
    }

    private companion object {
        /** No reset delay is used while the focus point is locked. */
        const val LOCKED_FOCUS_RESET_DELAY_MS = 0L

        /** Default reset delay after a regular autofocus tap. */
        const val AUTO_FOCUS_RESET_DELAY_MS = 3000L
    }
}
