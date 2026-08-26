package com.dns_technologies.mlkit_scanner.scanner.components.ui.focus

import android.view.View
import android.widget.FrameLayout

/** Listener invoked when focus should be requested at preview-relative offsets. */
typealias OnFocusRequestListener = (offsetX: Float, offsetY: Float) -> Unit

/**
 * Maps focus UI requests to camera autofocus calls.
 *
 * @property boundsView View whose bounds are used to convert normalized offsets to pixels.
 * @property focusView Overlay view that emits focus gestures and displays focus state.
 */
internal class FocusController(
    private val boundsView: FrameLayout,
    private val focusView: FocusView,
) {
    private var normalizedOffsetX = 0.0F
    private var normalizedOffsetY = 0.0F
    private var onAutoFocusRequest: OnFocusRequestListener? = null
    private var onLockedFocusRequest: OnFocusRequestListener? = null
    private var isWaitingForLayout = false
    private var isDisposed = false
    private val layoutChangeListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        if (hasBoundsSize()) {
            stopWaitingForLayout()
            applyFocusOffset()
        }
    }

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
        if (isDisposed) return
        this.onAutoFocusRequest = onAutoFocusRequest
        this.onLockedFocusRequest = onLockedFocusRequest
        applyFocusOffset()
    }

    /** Stores and applies the focus point offset used by the focus overlay. */
    fun updateCenter(widthOffset: Float, heightOffset: Float) {
        if (isDisposed) return
        normalizedOffsetX = widthOffset
        normalizedOffsetY = heightOffset
        applyFocusOffset()
    }

    /** Sends the latest mapped focus center to the owner. */
    private fun requestFocus(onFocusRequest: OnFocusRequestListener?) {
        if (isDisposed) return
        val (offsetX, offsetY) = mapFocusOffset()
        onFocusRequest?.invoke(offsetX, offsetY)
    }

    /** Applies the current focus offset after the parent view has valid dimensions. */
    private fun applyFocusOffset() {
        if (isDisposed) return
        if (!hasBoundsSize()) {
            if (!isWaitingForLayout) {
                isWaitingForLayout = true
                boundsView.addOnLayoutChangeListener(layoutChangeListener)
            }
            return
        }

        stopWaitingForLayout()
        val (offsetX, offsetY) = mapFocusOffset()
        focusView.setCenterOffset(offsetX, offsetY)
    }

    /** Removes callbacks and listeners owned by this focus mapping controller. */
    fun dispose() {
        if (isDisposed) return
        isDisposed = true
        stopWaitingForLayout()
        onAutoFocusRequest = null
        onLockedFocusRequest = null
        focusView.onAutoFocusRequested = null
        focusView.onLockFocusRequested = null
    }

    private fun stopWaitingForLayout() {
        if (!isWaitingForLayout) return
        isWaitingForLayout = false
        boundsView.removeOnLayoutChangeListener(layoutChangeListener)
    }

    /** Returns true when the bounds view can be used for pixel offset mapping. */
    private fun hasBoundsSize(): Boolean = boundsView.width != 0 && boundsView.height != 0

    /** Converts normalized visor offsets into PreviewView pixel offsets. */
    private fun mapFocusOffset(): Pair<Float, Float> = Pair(
        boundsView.width / 2F * normalizedOffsetX,
        boundsView.height / 2F * normalizedOffsetY,
    )
}
