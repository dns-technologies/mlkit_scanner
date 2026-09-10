package com.dns_technologies.mlkit_scanner.scanner.components.ui

import android.os.SystemClock
import android.view.MotionEvent
import android.widget.FrameLayout
import com.dns_technologies.mlkit_scanner.scanner.components.ui.focus.FocusView
import com.dns_technologies.mlkit_scanner.scanner.components.ui.visor.VisorView
import com.dns_technologies.mlkit_scanner.scanner.models.RecognizeVisorCropRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
internal class OverlayControllerTest {
    @Test
    fun `bind routes tap focus with visor-relative center and regular reset delay`() {
        val bounds = FrameLayout(RuntimeEnvironment.getApplication())
        val requests = mutableListOf<Triple<Long, Float, Float>>()
        val controller = OverlayController(bounds) { delay, x, y ->
            requests += Triple(delay, x, y)
        }
        controller.bindFocus()
        controller.setCropArea(
            RecognizeVisorCropRect(
                scaleWidth = 0.5,
                scaleHeight = 0.5,
                centerOffsetX = 0.5,
                centerOffsetY = -0.5,
            ),
        )
        bounds.layout(0, 0, 200, 100)
        val visor = bounds.findChild<VisorView>()
        visor.layout(0, 0, 200, 100)
        val now = SystemClock.uptimeMillis()

        assertTrue(controller.dispatchTouchEvent(event(now, now, MotionEvent.ACTION_DOWN)))
        assertTrue(controller.dispatchTouchEvent(event(now, now + 10, MotionEvent.ACTION_UP)))

        assertEquals(listOf(Triple(3000L, 50F, -25F)), requests)
    }

    @Test
    fun `unbind suppresses focus and dispose removes owned overlays`() {
        val bounds = FrameLayout(RuntimeEnvironment.getApplication())
        var requestCount = 0
        val controller = OverlayController(bounds) { _, _, _ -> requestCount += 1 }
        controller.bindFocus()
        controller.setCropArea(RecognizeVisorCropRect())
        assertEquals(2, bounds.childCount)

        controller.unbindFocus()
        val now = SystemClock.uptimeMillis()
        controller.dispatchTouchEvent(event(now, now, MotionEvent.ACTION_DOWN))
        controller.dispatchTouchEvent(event(now, now + 10, MotionEvent.ACTION_UP))
        assertEquals(0, requestCount)

        controller.dispose()

        assertEquals(0, bounds.childCount)
        assertFalse(controller.dispatchTouchEvent(event(0, 0, MotionEvent.ACTION_DOWN)))
        controller.bindFocus()
        assertEquals(0, bounds.childCount)
    }

    private inline fun <reified T> FrameLayout.findChild(): T =
        (0 until childCount).map { getChildAt(it) }.filterIsInstance<T>().single()

    private fun event(downTime: Long, eventTime: Long, action: Int): MotionEvent =
        MotionEvent.obtain(downTime, eventTime, action, 50F, 50F, 0)
}
