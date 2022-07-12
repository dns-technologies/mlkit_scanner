package com.dns_technologies.mlkit_scanner

import android.view.View
import com.otaliastudios.cameraview.CameraView
/**
 * Set [CenterFocusView] in the center of a [CameraView]
 *
 * By long tap, focus is locked on the [CameraView] center. By pressing, focusing in the [CameraView]
 * center occurs without locking.
 *
 * Use [widthOffset] or [heightOffset] to move the center of camera focus
 */
fun CameraView.useCenterFocus(widthOffset: Float, heightOffset: Float) {
    if (cameraOptions?.isAutoFocusSupported == true) {
        val (horizontalMargin, verticalMargin) = calcAdaptiveMargins(resources.configuration.orientation, width, widthOffset, height, heightOffset)
        val focusView = CenterFocusView(context, horizontalMargin.toInt(), verticalMargin.toInt())
        focusView.setAutoFocusSetListener { needLock ->
            val (h, v) = calcAdaptiveMargins(resources.configuration.orientation, width, widthOffset, height, heightOffset)
            if (needLock) {
                focusOnCenter(0, h, v)
            } else {
                focusOnCenter(3000, h, v)
            }
        }
        setOnTouchListener { view, event ->
            view.performClick()
            focusView.onTouchEvent(event)
        }
        addCameraListener(focusView.cameraListener)
        addView(focusView)
    }
}

/**
 * Adding overlay on [CameraView]
 *
 * Check if a [CenterFocusView] is using. If so, the overlay is drawn under the view
 */
fun CameraView.addOverlay(view: View) {
    for (i in 0..childCount) {
        if (getChildAt(i) is CenterFocusView) {
            addView(view, i)
            return
        }
    }
    addView(view)
}

/**
 * Remove [CenterFocusView] from a [CameraView] center
 */
fun CameraView.removeCenterFocus() {
    setOnTouchListener(null)
    for (i in 0..childCount) {
        val child = getChildAt(i)
        if (child is CenterFocusView) {
            removeView(child)
            removeCameraListener(child.cameraListener)
            return
        }
    }
}

/**
 * Camera center focus
 *
 * [resetDelay] in milliseconds to reset the focus after a metering event.
 *
 * Use [horizontalMargin] and [verticalMargin] to shift the center of focus
 */
private fun CameraView.focusOnCenter(resetDelay: Long, horizontalMargin: Float, verticalMargin: Float) {
    autoFocusResetDelay = resetDelay
    startAutoFocus(width / 2 + horizontalMargin, height / 2 + verticalMargin)
}

/**
 * Calculate margins depending on device [orientation]
 */
private fun calcAdaptiveMargins(orientation: Int, width: Int, offsetWidth: Float, height: Int, offsetHeight: Float): Pair<Float, Float> = when (orientation) {
        1 -> Pair(width * offsetWidth, height * offsetHeight)
        else -> Pair(width * offsetHeight, height * offsetWidth)
    }