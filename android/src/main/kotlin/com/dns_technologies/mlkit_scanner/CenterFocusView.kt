package com.dns_technologies.mlkit_scanner

import android.annotation.SuppressLint
import android.content.Context
import android.view.*
import android.view.animation.*
import android.widget.FrameLayout
import com.otaliastudios.cameraview.CameraListener
import com.otaliastudios.cameraview.CameraOptions

/** Handle a gesture for an autofocus realisation and draw a focus lock and an autofocus animation */
@SuppressLint("ViewConstructor")
class CenterFocusView constructor(
    context: Context,
    private var center: Pair<Float, Float>,
) : FrameLayout(context), Animation.AnimationListener,
    View.OnLayoutChangeListener {
    private lateinit var lock: View
    private lateinit var circle: View
    private val fadeAnimation = AnimationUtils.loadAnimation(context, R.anim.fade)
    private val fadeOutAnimation = AnimationUtils.loadAnimation(context, R.anim.fade_out)
    private var autoFocusSetListener: ((Boolean) -> Unit)? = null
    private val gestureDetector: GestureDetector
    private val fadeInAnimation = AlphaAnimation(0F, 1F).apply {
        duration = 300
        fillAfter = true
    }
    private val autofocusLockedFinalPosition: Pair<Float, Float>
        get() = Pair(lock.width * 0.8F - lock.x, lock.height * 0.8F - lock.y)
    val cameraListener: CameraListener

    init {
        fadeInAnimation.setAnimationListener(this)
        fadeOutAnimation.setAnimationListener(this)
        gestureDetector = createGestureDetector()

        cameraListener = object : CameraListener() {
            override fun onCameraOpened(options: CameraOptions) {
                releaseLock()
            }
        }
        val inflater = LayoutInflater.from(context)
        val layout = inflater.inflate(R.layout.center_focus_layout, null)

        addView(layout)
    }

    /**
     * Sets focus center with offsets [horizontalOffset] and [verticalOffset]
     */
    fun setFocusCenter(horizontalOffset: Float = 0.0F, verticalOffset: Float = 0.0F) {
        center = Pair(horizontalOffset, verticalOffset)
        circle.apply {
            translationX = horizontalOffset
            translationY = verticalOffset
        }
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

    /**
     * Set an auto focus gesture listener.
     *
     * [listener] - a closure with a bool parameter that indicates need to lock a focus
     */
    fun setAutoFocusSetListener(listener: (Boolean) -> Unit) {
        autoFocusSetListener = listener
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
            lock.apply {
                val animation = buildLockAnimation()
                startAnimation(animation)
            }
        }
    }

    private fun buildLockAnimation(): Animation {
        val set = AnimationSet(false).apply {
            fillAfter = true
        }

        set.addAnimation(fadeInAnimation)
        val translateAnimation = lockMovementAnimation()
        set.addAnimation(translateAnimation)
        return set
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
        if (l == oldL && t == oldT && r == oldR && b == oldB) {
            return
        }
        if (lock.visibility == View.VISIBLE) {
            val (deltaX, deltaY) = autofocusLockedFinalPosition
            lock.apply {
                val translateAnimation = TranslateAnimation(deltaX, deltaX, deltaY, deltaY).apply {
                    startOffset = 0
                    duration = 0
                    fillAfter = true
                }
                startAnimation(translateAnimation)
            }
        }
    }
}