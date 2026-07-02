package com.dns_technologies.mlkit_scanner.scanner.components.ui.focus

import android.content.res.Configuration
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout

private typealias OnFocusCenterListener = (resetDelayMs: Long, offsetX: Float, offsetY: Float) -> Unit

/** Coordinates focus UI placement, touch handling and backend autofocus requests. */
class FocusController(
    private val parent: FrameLayout,
    private val focus: Focus,
) {
    private var focusCenter: Pair<Float, Float> = INITIAL_FOCUS_CENTER
    private var onFocusCenter: OnFocusCenterListener? = null

    /** Enables center focus handling and restores the last requested focus offset. */
    fun bind(onFocusCenter: OnFocusCenterListener) {
        this.onFocusCenter = onFocusCenter
        ensureFocus()
        applyFocusCenter(focusCenter.first, focusCenter.second)
    }

    /** Stores and applies the focus point offset used by the focus overlay. */
    fun updateCenter(widthOffset: Float, heightOffset: Float) {
        focusCenter = Pair(widthOffset, heightOffset)
        applyFocusCenter(widthOffset, heightOffset)
    }

    /** Adds a view below the focus overlay when focus is already attached. */
    fun addViewBelowFocus(view: View) {
        if (focus.parent === parent) {
            parent.addView(view, parent.indexOfChild(focus))
        } else {
            parent.addView(view)
        }
    }

    /** Forwards the touch event to the focus overlay and reports click completion. */
    fun handleTouch(event: MotionEvent, onClick: () -> Unit): Boolean {
        if (focus.parent !== parent) return false

        if (event.action == MotionEvent.ACTION_UP) {
            onClick()
        }
        focus.onTouchEvent(event)
        return true
    }

    /** Adds the focus overlay to the parent if it is not attached yet. */
    private fun ensureFocus() {
        if (focus.parent === parent) return
        parent.addView(focus)
    }

    /** Applies focus center offsets after the parent view has valid dimensions. */
    private fun applyFocusCenter(widthOffset: Float, heightOffset: Float) {
        if (parent.width == 0 || parent.height == 0) {
            parent.post { applyFocusCenter(widthOffset, heightOffset) }
            return
        }

        val (horizontalOffset, verticalOffset) = calcAdaptiveOffsets(
            parent.resources.configuration.orientation,
            parent.width,
            widthOffset,
            parent.height,
            heightOffset,
        )
        focus.setAutoFocusSetListener { needLock ->
            onFocusCenter?.invoke(
                if (needLock) LOCKED_FOCUS_RESET_DELAY_MS else AUTO_FOCUS_RESET_DELAY_MS,
                horizontalOffset,
                verticalOffset,
            )
        }
        focus.setFocusCenter(horizontalOffset, verticalOffset)
    }

    /** Converts normalized scanner offsets into orientation-aware pixel offsets. */
    private fun calcAdaptiveOffsets(
        orientation: Int,
        width: Int,
        offsetWidth: Float,
        height: Int,
        offsetHeight: Float,
    ): Pair<Float, Float> = when (orientation) {
        Configuration.ORIENTATION_PORTRAIT -> Pair(width / 2F * offsetWidth, height / 2F * offsetHeight)
        else -> Pair(width / 2F * -offsetHeight, height / 2F * -offsetWidth)
    }

    companion object {
        /** Initial focus overlay offset before scanner-specific offsets are applied. */
        val INITIAL_FOCUS_CENTER = Pair(0.0F, 0.0F)

        /** No reset delay is used while the focus point is locked. */
        const val LOCKED_FOCUS_RESET_DELAY_MS = 0L

        /** Default reset delay after a regular autofocus tap. */
        const val AUTO_FOCUS_RESET_DELAY_MS = 3000L
    }
}
