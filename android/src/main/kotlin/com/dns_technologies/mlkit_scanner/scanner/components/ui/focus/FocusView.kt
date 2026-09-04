package com.dns_technologies.mlkit_scanner.scanner.components.ui.focus

import android.annotation.SuppressLint
import android.content.Context
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.animation.AnimationSet
import android.view.animation.AnimationUtils
import android.view.animation.TranslateAnimation
import android.widget.FrameLayout
import com.dns_technologies.mlkit_scanner.R
import androidx.core.view.isVisible
import androidx.core.view.isInvisible

/**
 * Draws focus/lock animation over camera preview and emits focus gestures.
 *
 * @param context Android context used to inflate focus UI and animations.
 */
@SuppressLint("ViewConstructor")
class FocusView(
    context: Context,
) : FrameLayout(context), Animation.AnimationListener, View.OnLayoutChangeListener {
    private var centerOffsetX = 0.0F
    private var centerOffsetY = 0.0F
    private val lock: View
    private val circle: View
    private var isDisposed = false
    private val fadeAnimation = AnimationUtils.loadAnimation(context, R.anim.fade)
    private val fadeOutAnimation = AnimationUtils.loadAnimation(context, R.anim.fade_out)
    private val gestureDetector: GestureDetector = createGestureDetector()
    private val fadeInAnimation = AlphaAnimation(0F, 1F).apply {
        duration = 300
        fillAfter = true
    }

    /** Invoked when a tap requests regular autofocus. */
    var onAutoFocusRequested: (() -> Unit)? = null

    /** Invoked when a long press requests locked autofocus. */
    var onLockFocusRequested: (() -> Unit)? = null

    private val autofocusLockedFinalPosition: Pair<Float, Float>
        get() = Pair(lock.width * 0.8F - lock.x, lock.height * 0.8F - lock.y)

    init {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        fadeInAnimation.setAnimationListener(this)
        fadeOutAnimation.setAnimationListener(this)
        LayoutInflater.from(context).inflate(R.layout.center_focus_layout, this, true)
        lock = findViewById(R.id.lockImage)
        circle = findViewById(R.id.circle)
    }

    /** Starts observing lock layout changes while the focus overlay is attached. */
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!isDisposed) lock.addOnLayoutChangeListener(this)
    }

    /** Removes focus layout listeners before the view leaves the window. */
    override fun onDetachedFromWindow() {
        lock.removeOnLayoutChangeListener(this)
        super.onDetachedFromWindow()
    }

    /** Delegates touch events to the focus gesture detector. */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        return gestureDetector.onTouchEvent(event)
    }

    /** Updates lock visibility before focus animations start. */
    override fun onAnimationStart(animation: Animation?) {
        when (animation) {
            fadeInAnimation -> lock.visibility = View.VISIBLE
            fadeOutAnimation -> lock.visibility = View.INVISIBLE
        }
    }

    /** Handles the end of focus animations. */
    override fun onAnimationEnd(animation: Animation?) {
    }

    /** Handles repeating focus animations. */
    override fun onAnimationRepeat(animation: Animation?) {
    }

    /** Reapplies lock indicator position when its layout bounds change. */
    override fun onLayoutChange(
        v: View?,
        l: Int,
        t: Int,
        r: Int,
        b: Int,
        oldL: Int,
        oldT: Int,
        oldR: Int,
        oldB: Int,
    ) {
        // exclude parasite redraws
        if (l == oldL && t == oldT && r == oldR && b == oldB) return

        if (lock.isVisible) {
            val (deltaX, deltaY) = autofocusLockedFinalPosition
            lock.startAnimation(
                TranslateAnimation(deltaX, deltaX, deltaY, deltaY).apply {
                    startOffset = 0
                    duration = 0
                    fillAfter = true
                }
            )
        }
    }

    /** Updates the visual focus center offset relative to the preview center. */
    fun setCenterOffset(x: Float, y: Float) {
        if (isDisposed) return
        centerOffsetX = x
        centerOffsetY = y
        circle.apply {
            translationX = x
            translationY = y
        }
    }

    /** Clears the visual lock when camera ownership leaves this preview. */
    fun resetIndicator() {
        if (isDisposed) return
        clearAnimation()
        lock.clearAnimation()
        circle.clearAnimation()
        lock.visibility = View.INVISIBLE
    }

    /** Cancels focus callbacks, layout listeners and animations owned by this overlay. */
    fun dispose() {
        if (isDisposed) return
        isDisposed = true
        onAutoFocusRequested = null
        onLockFocusRequested = null
        lock.removeOnLayoutChangeListener(this)
        clearAnimation()
        lock.clearAnimation()
        circle.clearAnimation()
        fadeInAnimation.setAnimationListener(null)
        fadeOutAnimation.setAnimationListener(null)
    }

    /** Creates the gesture detector that maps taps and long presses to focus actions. */
    private fun createGestureDetector(): GestureDetector {
        return GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                autoFocus()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                lockFocus()
            }
        })
    }

    /** Starts regular autofocus and releases a visible focus lock. */
    private fun autoFocus() {
        onAutoFocusRequested?.invoke()
        releaseLock()
        circle.startAnimation(fadeAnimation)
    }

    /** Hides the focus lock indicator when it is visible. */
    private fun releaseLock() {
        if (lock.isVisible) {
            lock.startAnimation(fadeOutAnimation)
        }
    }

    /** Starts autofocus lock animation and notifies the listener about lock state. */
    private fun lockFocus() {
        onLockFocusRequested?.invoke()
        circle.startAnimation(fadeAnimation)
        if (lock.isInvisible) {
            lock.startAnimation(buildLockAnimation())
        }
    }

    /** Builds the combined fade and movement animation for the lock indicator. */
    private fun buildLockAnimation(): Animation {
        return AnimationSet(false).apply {
            fillAfter = true
            addAnimation(fadeInAnimation)
            addAnimation(lockMovementAnimation())
        }
    }

    /** Builds the lock indicator movement animation from focus center to final position. */
    private fun lockMovementAnimation(): Animation {
        val (deltaX, deltaY) = autofocusLockedFinalPosition
        return TranslateAnimation(centerOffsetX, deltaX, centerOffsetY, deltaY).apply {
            startOffset = 300
            duration = 500
        }
    }
}
