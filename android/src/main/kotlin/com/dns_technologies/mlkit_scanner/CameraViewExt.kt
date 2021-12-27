package com.dns_technologies.mlkit_scanner

import android.view.View
import com.otaliastudios.cameraview.CameraView
/**
 * Set [CenterFocusView] in the center of a [CameraView]
 *
 * By long tap, focus is locked on the [CameraView] center. By pressing, focusing in the [CameraView]
 * center occurs without locking.
 */
fun CameraView.useCenterFocus() {
    if (cameraOptions?.isAutoFocusSupported == true) {
        val focusView = CenterFocusView(context)
        focusView.setAutoFocusSetListener { needLock ->
            if (needLock) {
                focusOnCenter(0)
            } else {
                focusOnCenter(3000)
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

private fun CameraView.focusOnCenter(resetDelay: Long) {
    autoFocusResetDelay = resetDelay
    startAutoFocus(width / 2F, height / 2F)
}