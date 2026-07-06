package com.dns_technologies.mlkit_scanner.scanner.components.ui.focus

import android.content.res.Configuration
import android.widget.FrameLayout

typealias OnFocusRequestListener = (offsetX: Float, offsetY: Float) -> Unit

/** Maps focus UI requests to camera autofocus calls. */
class FocusController(
    private val boundsView: FrameLayout,
    private val focusView: FocusView,
) {
    private var normalizedOffsetX = 0.0F
    private var normalizedOffsetY = 0.0F
    private var onAutoFocusRequest: OnFocusRequestListener? = null
    private var onLockedFocusRequest: OnFocusRequestListener? = null

    init {
        focusView.onAutoFocusRequested = {
            requestFocus(onAutoFocusRequest)
        }
        focusView.onLockFocusRequested = {
            requestFocus(onLockedFocusRequest)
        }
    }

    /** Enables focus handling and restores the last requested focus offset. */
    fun bind(
        onAutoFocusRequest: OnFocusRequestListener,
        onLockedFocusRequest: OnFocusRequestListener,
    ) {
        this.onAutoFocusRequest = onAutoFocusRequest
        this.onLockedFocusRequest = onLockedFocusRequest
        applyFocusOffset()
    }

    /** Stores and applies the focus point offset used by the focus overlay. */
    fun updateCenter(widthOffset: Float, heightOffset: Float) {
        normalizedOffsetX = widthOffset
        normalizedOffsetY = heightOffset
        applyFocusOffset()
    }

    /** Sends the latest mapped focus center to the owner. */
    private fun requestFocus(onFocusRequest: OnFocusRequestListener?) {
        val (offsetX, offsetY) = mapFocusOffset()
        onFocusRequest?.invoke(offsetX, offsetY)
    }

    /** Applies the current focus offset after the parent view has valid dimensions. */
    private fun applyFocusOffset() {
        if (!hasBoundsSize()) {
            boundsView.post { applyFocusOffset() }
            return
        }

        val (offsetX, offsetY) = mapFocusOffset()
        focusView.setCenterOffset(offsetX, offsetY)
    }

    /** Returns true when the bounds view can be used for pixel offset mapping. */
    private fun hasBoundsSize(): Boolean = boundsView.width != 0 && boundsView.height != 0

    /** Converts normalized scanner offsets into orientation-aware pixel offsets. */
    private fun mapFocusOffset(): Pair<Float, Float> {
        val width = boundsView.width
        val height = boundsView.height
        return when (boundsView.resources.configuration.orientation) {
            Configuration.ORIENTATION_PORTRAIT -> Pair(
                width / 2F * normalizedOffsetX,
                height / 2F * normalizedOffsetY,
            )
            else -> Pair(
                width / 2F * -normalizedOffsetY,
                height / 2F * -normalizedOffsetX,
            )
        }
    }
}
