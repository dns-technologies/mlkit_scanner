package com.dns_technologies.mlkit_scanner.scanner.components.ui

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

/** Handles autofocus gestures and draws focus/lock animation over camera preview. */
@SuppressLint("ViewConstructor")
class CenterFocusView(
    context: Context,
    private var center: Pair<Float, Float>,
) : FrameLayout(context), Animation.AnimationListener, View.OnLayoutChangeListener {
    private lateinit var lock: View
    private lateinit var circle: View
    private val fadeAnimation = AnimationUtils.loadAnimation(context, R.anim.fade)
    private val fadeOutAnimation = AnimationUtils.loadAnimation(context, R.anim.fade_out)
    private var autoFocusSetListener: ((Boolean) -> Unit)? = null
    private val gestureDetector: GestureDetector = createGestureDetector()
    private val fadeInAnimation = AlphaAnimation(0F, 1F).apply {
        duration = 300
        fillAfter = true
    }
    private val autofocusLockedFinalPosition: Pair<Float, Float>
        get() = Pair(lock.width * 0.8F - lock.x, lock.height * 0.8F - lock.y)

    init {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        fadeInAnimation.setAnimationListener(this)
        fadeOutAnimation.setAnimationListener(this)
        addView(LayoutInflater.from(context).inflate(R.layout.center_focus_layout, null))
    }

    fun setFocusCenter(horizontalOffset: Float = 0.0F, verticalOffset: Float = 0.0F) {
        center = Pair(horizontalOffset, verticalOffset)
        circle.apply {
            translationX = horizontalOffset
            translationY = verticalOffset
        }
    }

    fun setAutoFocusSetListener(listener: (Boolean) -> Unit) {
        autoFocusSetListener = listener
    }

    private fun createGestureDetector(): GestureDetector {
        return GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                autoFocus()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                lockFocus()
            }
        })
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        lock = findViewById(R.id.lockImage)
        circle = findViewById(R.id.circle)
        lock.addOnLayoutChangeListener(this)
    }

    override fun onDetachedFromWindow() {
        lock.removeOnLayoutChangeListener(this)
        super.onDetachedFromWindow()
    }

    private fun autoFocus() {
        autoFocusSetListener?.invoke(false)
        releaseLock()
        circle.startAnimation(fadeAnimation)
    }

    private fun releaseLock() {
        if (lock.visibility == View.VISIBLE) {
            lock.startAnimation(fadeOutAnimation)
        }
    }

    private fun lockFocus() {
        autoFocusSetListener?.invoke(true)
        circle.startAnimation(fadeAnimation)
        if (lock.visibility == View.INVISIBLE) {
            lock.startAnimation(buildLockAnimation())
        }
    }

    private fun buildLockAnimation(): Animation {
        return AnimationSet(false).apply {
            fillAfter = true
            addAnimation(fadeInAnimation)
            addAnimation(lockMovementAnimation())
        }
    }

    private fun lockMovementAnimation(): Animation {
        val (deltaX, deltaY) = autofocusLockedFinalPosition
        return TranslateAnimation(center.first, deltaX, center.second, deltaY).apply {
            startOffset = 300
            duration = 500
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        return gestureDetector.onTouchEvent(event)
    }

    override fun onAnimationStart(animation: Animation?) {
        when (animation) {
            fadeInAnimation -> lock.visibility = View.VISIBLE
            fadeOutAnimation -> lock.visibility = View.INVISIBLE
        }
    }

    override fun onAnimationEnd(animation: Animation?) {
    }

    override fun onAnimationRepeat(animation: Animation?) {
    }

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

        if (lock.visibility == View.VISIBLE) {
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
}
