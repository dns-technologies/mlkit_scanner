package com.dns_technologies.mlkit_scanner

import android.view.View
import com.otaliastudios.cameraview.CameraView

/**
 * Set [CenterFocusView] in the center of a [CameraView]
 *
 * By long tap, focus is locked on the [CameraView] center. By pressing, focusing in the [CameraView]
 * center occurs without locking.
 */
fun CameraView.useCenterFocus(center: Pair<Float, Float>) {
    if (cameraOptions?.isAutoFocusSupported == true) {
        val focusView = CenterFocusView(context, center)
        focusView.setAutoFocusSetListener { needLock ->
            focusOnCenter(if (needLock) 0 else 3000)
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
 * Changes focus relative to the center of [CameraView]
 *
 * Use [widthOffset] or [heightOffset] to move the center of camera focus
 *
 * If autofocus is not supported, then nothing will happen
 */
fun CameraView.changeFocusCenter(widthOffset: Float = 0.0F, heightOffset: Float = 0.0F) {
    if (cameraOptions?.isAutoFocusSupported != true) {
        return
    }
    for (i in 0..childCount) {
        val child = getChildAt(i)
        if (child is CenterFocusView) {
            val (h, v) = calcAdaptiveOffsets(
                resources.configuration.orientation,
                width,
                widthOffset,
                height,
                heightOffset
            )
            child.setAutoFocusSetListener { needLock ->
                focusOnCenter(if (needLock) 0 else 3000, h, v)
            }
            child.setFocusCenter(h, v)
            return
        }
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
 * Use [offsetX] and [offsetY] to shift the center of focus
 */
private fun CameraView.focusOnCenter(
    resetDelay: Long,
    offsetX: Float = 0.0F,
    offsetY: Float = 0.0F
) {
    autoFocusResetDelay = resetDelay
    startAutoFocus(width / 2 + offsetX, height / 2 + offsetY)
}

/**
 * Calculate offsets depending on device [orientation]
 */
private fun calcAdaptiveOffsets(
    orientation: Int,
    width: Int,
    offsetWidth: Float,
    height: Int,
    offsetHeight: Float
): Pair<Float, Float> = when (orientation) {
    1 -> Pair(width / 2 * offsetWidth, height / 2 * offsetHeight)
    else -> Pair(width / 2 * -offsetHeight, height / 2 * -offsetWidth)
}