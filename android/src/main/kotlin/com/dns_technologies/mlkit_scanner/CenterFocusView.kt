package com.dns_technologies.mlkit_scanner

import android.annotation.SuppressLint
import android.content.Context
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.animation.*
import android.widget.FrameLayout
import com.otaliastudios.cameraview.CameraListener
import com.otaliastudios.cameraview.CameraOptions

/** Handle a gesture for an auto focus realisation and draw a focus lock and an auto focus animation */
class CenterFocusView(context: Context): FrameLayout(context), Animation.AnimationListener {
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
    }

    private fun createGestureDetector(): GestureDetector {
        return GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent?): Boolean {
                autoFocus()
                return true
            }

            override fun onLongPress(e: MotionEvent?) {
                lockFocus()
            }
        })
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val inflater = LayoutInflater.from(context)
        val layout = inflater.inflate(R.layout.center_focus_layout, null)
        addView(layout)
        lock = findViewById(R.id.lockImage)
        circle = findViewById(R.id.circle)
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
                val animation = buildLockAnimation(-x + height * 0.8F, -y + height * 0.8F)
                startAnimation(animation)
            }
        }
    }

    private fun buildLockAnimation(x: Float, y: Float): Animation {
        val set = AnimationSet(false).apply {
            fillAfter = true
        }

        set.addAnimation(fadeInAnimation)
        val translateAnimation = TranslateAnimation(0F, x, 0F, y).apply {
            startOffset = 300
            duration = 500
        }
        set.addAnimation(translateAnimation)
        return set
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent?): Boolean {
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
}