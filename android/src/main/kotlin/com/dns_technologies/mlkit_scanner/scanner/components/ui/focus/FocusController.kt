package com.dns_technologies.mlkit_scanner.scanner.components.ui.focus

/** Listener invoked when focus should be requested at preview-relative offsets. */
typealias OnFocusRequestListener = (offsetX: Float, offsetY: Float) -> Unit

/**
 * Maps focus UI requests to camera autofocus calls.
 *
 * @property focusView Overlay view that emits focus gestures and displays focus state.
 */
internal class FocusController(
    private val focusView: FocusView,
) {
    private var focusOffsetX = 0.0F
    private var focusOffsetY = 0.0F
    private var onAutoFocusRequest: OnFocusRequestListener? = null
    private var onLockedFocusRequest: OnFocusRequestListener? = null
    private var isDisposed = false

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
    }

    /** Stops forwarding focus gestures until this preview becomes active again. */
    fun unbind() {
        onAutoFocusRequest = null
        onLockedFocusRequest = null
    }

    /** Applies the already-resolved visor center to visual and camera focus requests. */
    fun updateCenter(offsetX: Float, offsetY: Float) {
        if (isDisposed) return
        focusOffsetX = offsetX
        focusOffsetY = offsetY
        focusView.setCenterOffset(focusOffsetX, focusOffsetY)
    }

    /** Sends the latest mapped focus center to the owner. */
    private fun requestFocus(onFocusRequest: OnFocusRequestListener?) {
        if (isDisposed) return
        onFocusRequest?.invoke(focusOffsetX, focusOffsetY)
    }

    /** Removes callbacks and listeners owned by this focus mapping controller. */
    fun dispose() {
        if (isDisposed) return
        isDisposed = true
        unbind()
        focusView.onAutoFocusRequested = null
        focusView.onLockFocusRequested = null
    }
}
