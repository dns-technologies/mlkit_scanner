package com.dns_technologies.mlkit_scanner.scanner.components.ui.focus

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import com.dns_technologies.mlkit_scanner.R
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
internal class FocusViewTest {
    @Test
    fun `set center moves the visible focus indicator`() {
        val view = FocusView(RuntimeEnvironment.getApplication())
        val circle = view.findViewById<View>(R.id.circle)

        view.setCenterOffset(25F, -10F)

        assertEquals(25F, circle.translationX, 0F)
        assertEquals(-10F, circle.translationY, 0F)
    }

    @Test
    fun `tap and long press publish their respective focus intents`() {
        val view = FocusView(RuntimeEnvironment.getApplication())
        var autoFocusCount = 0
        var lockFocusCount = 0
        view.onAutoFocusRequested = { autoFocusCount += 1 }
        view.onLockFocusRequested = { lockFocusCount += 1 }
        val downTime = SystemClock.uptimeMillis()

        view.onTouchEvent(event(downTime, downTime, MotionEvent.ACTION_DOWN))
        view.onTouchEvent(event(downTime, downTime + 10, MotionEvent.ACTION_UP))
        view.onTouchEvent(event(downTime + 100, downTime + 100, MotionEvent.ACTION_DOWN))
        ShadowLooper.idleMainLooper(600, TimeUnit.MILLISECONDS)

        assertEquals(1, autoFocusCount)
        assertEquals(1, lockFocusCount)
    }

    @Test
    fun `reset hides the lock and dispose makes later updates inert`() {
        val view = FocusView(RuntimeEnvironment.getApplication())
        val lock = view.findViewById<View>(R.id.lockImage)
        val circle = view.findViewById<View>(R.id.circle)
        lock.visibility = View.VISIBLE
        view.setCenterOffset(10F, 20F)

        view.resetIndicator()
        assertEquals(View.INVISIBLE, lock.visibility)

        view.dispose()
        view.setCenterOffset(30F, 40F)

        assertNull(view.onAutoFocusRequested)
        assertNull(view.onLockFocusRequested)
        assertEquals(10F, circle.translationX, 0F)
        assertEquals(20F, circle.translationY, 0F)
    }

    private fun event(downTime: Long, eventTime: Long, action: Int): MotionEvent =
        MotionEvent.obtain(downTime, eventTime, action, 50F, 50F, 0)
}
