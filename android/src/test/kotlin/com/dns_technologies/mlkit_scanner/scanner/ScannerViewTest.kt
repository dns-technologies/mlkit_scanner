package com.dns_technologies.mlkit_scanner.scanner

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
internal class ScannerViewTest {
    @Test
    fun `attach moves preview into the view and reports ready after non-zero layout`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val preview = View(activity)
        val scanner = mock(Scanner::class.java)
        doReturn(preview).`when`(scanner).previewView
        val view = ScannerView(activity, scanner, { _, _, _ -> }, {})
        activity.setContentView(view)
        var readyCount = 0

        view.attachPreview { readyCount += 1 }
        preview.layout(0, 0, 200, 100)
        preview.viewTreeObserver.dispatchOnPreDraw()

        assertTrue(view.hasPreview())
        assertTrue(view.isPreviewReady())
        assertEquals(1, readyCount)
        assertSame(view, view.view)
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, view.layoutParams.width)
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, view.layoutParams.height)
    }

    @Test
    fun `detach and dispose clear only local view resources`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val preview = View(activity)
        val scanner = mock(Scanner::class.java)
        doReturn(preview).`when`(scanner).previewView
        var disposeCount = 0
        val view = ScannerView(activity, scanner, { _, _, _ -> }) { disposeCount += 1 }
        activity.setContentView(view)
        view.attachPreview {}

        view.detachPreview()
        assertFalse(view.hasPreview())
        assertFalse(view.isPreviewReady())

        view.dispose()
        view.dispose()
        view.attachPreview {}

        assertEquals(1, disposeCount)
        assertFalse(view.hasPreview())
        assertTrue(view.performClick())
    }

    @Test
    fun `session disposal does not invoke the platform disposal callback`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val scanner = mock(Scanner::class.java)
        doReturn(View(activity)).`when`(scanner).previewView
        var disposeCount = 0
        val view = ScannerView(activity, scanner, { _, _, _ -> }) { disposeCount += 1 }

        view.disposeFromSession()
        view.dispose()

        assertEquals(0, disposeCount)
    }
}
