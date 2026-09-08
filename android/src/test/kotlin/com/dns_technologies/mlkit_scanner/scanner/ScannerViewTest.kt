package com.dns_technologies.mlkit_scanner.scanner

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
internal class ScannerViewTest {
    @Test
    fun `Flutter routing id survives Android id changes and disposal`() = withView { f ->
        assertEquals(42, f.view.viewId)

        f.view.id = 100
        f.view.dispose()

        assertEquals(100, f.view.id)
        assertEquals(42, f.view.viewId)
    }

    @Test
    fun `attach moves preview into the view and reports ready after non-zero layout`() = withView { f ->
        var readyCount = 0
        f.view.attachPreview(f.preview) { readyCount += 1 }
        f.drawPreview()

        assertTrue(f.view.hasPreview())
        assertTrue(f.view.isPreviewReady())
        assertEquals(1, readyCount)
        assertSame(f.view, f.view.view)
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, f.view.layoutParams.width)
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, f.view.layoutParams.height)
    }

    @Test
    fun `detach and dispose clear only local view resources`() = withView { f ->
        f.view.attachPreview(f.preview) {}

        f.view.detachPreview()
        assertFalse(f.view.hasPreview())
        assertFalse(f.view.isPreviewReady())

        f.view.dispose()
        f.view.dispose()
        f.view.release()
        f.view.attachPreview(f.preview) {}

        assertEquals(1, f.disposeCount)
        assertFalse(f.view.hasPreview())
        assertTrue(f.view.performClick())
    }

    @Test
    fun `release does not invoke the disposal callback or allow later attachment`() = withView { f ->
        f.view.attachPreview(f.preview) {}
        f.view.release()
        f.view.release()
        f.view.dispose()
        f.view.attachPreview(f.preview) {}

        assertEquals(0, f.disposeCount)
        assertFalse(f.view.hasPreview())
        assertFalse(f.view.isPreviewReady())
        assertEquals(null, f.preview.parent)
    }

    @Test
    fun `release clears readiness of an already laid out preview`() = withView { f ->
        f.view.attachPreview(f.preview) {}
        f.drawPreview()
        assertTrue(f.view.isPreviewReady())

        f.view.release()

        assertFalse(f.view.isPreviewReady())
        assertEquals(0, f.view.childCount)
    }

    @Test
    fun `disposal callback failure still releases the view exactly once`() = withView { f ->
        val failure = IllegalStateException("Disposal callback failed")
        var readyCount = 0
        f.onDispose = { throw failure }
        f.view.attachPreview(f.preview) { readyCount += 1 }

        assertSame(failure, runCatching { f.view.dispose() }.exceptionOrNull())
        f.view.dispose()
        f.view.release()
        f.view.attachPreview(f.preview) { readyCount += 1 }
        f.drawPreview()

        assertEquals(1, f.disposeCount)
        assertEquals(0, readyCount)
        assertFalse(f.view.hasPreview())
        assertEquals(0, f.view.childCount)
    }

    @Test
    fun `disposal notifies before cleanup and rejects reentrant disposal or attachment`() = withView { f ->
        f.view.attachPreview(f.preview) {}
        f.onDispose = {
            assertTrue(f.view.hasPreview())
            f.view.dispose()
            f.view.release()
            f.view.attachPreview(f.preview) { error("Disposed view must not become ready") }
        }

        f.view.dispose()
        f.drawPreview()

        assertEquals(1, f.disposeCount)
        assertFalse(f.view.hasPreview())
        assertFalse(f.view.isPreviewReady())
        assertEquals(0, f.view.childCount)
    }

    @Test
    fun `releasing a former host does not detach the preview from its new host`() {
        for (notify in listOf(false, true)) withView { f ->
            f.view.attachPreview(f.preview) {}
            val next = ScannerView(f.activity, 43, { _, _, _ -> }, {})
            try {
                next.attachPreview(f.preview) {}
                if (notify) f.view.dispose() else f.view.release()

                assertFalse(f.view.hasPreview())
                assertTrue(next.hasPreview())
                assertSame(next, f.preview.parent)
                assertEquals(if (notify) 1 else 0, f.disposeCount)
            } finally {
                next.release()
            }
        }
    }

    @Test
    fun `release cancels a pending preview readiness callback`() = withView { f ->
        var readyCount = 0
        f.view.attachPreview(f.preview) { readyCount += 1 }

        f.view.release()
        f.drawPreview()

        assertEquals(0, readyCount)
        assertFalse(f.view.isPreviewReady())
    }

    @Test
    fun `preview readiness waits for a non-zero layout and is reported only once`() = withView { f ->
        var readyCount = 0
        f.view.attachPreview(f.preview) { readyCount += 1 }

        f.drawPreview(width = 0, height = 0)
        assertEquals(0, readyCount)
        assertFalse(f.view.isPreviewReady())

        f.drawPreview()
        f.drawPreview()

        assertEquals(1, readyCount)
        assertTrue(f.view.isPreviewReady())
    }

    @Test
    fun `replaced readiness listener cannot complete from an old pre-draw snapshot`() = withView { f ->
        var oldReady = 0
        var newReady = 0
        val observer = f.view.viewTreeObserver
        observer.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                observer.removeOnPreDrawListener(this)
                f.view.attachPreview(f.preview) { newReady++ }
                return true
            }
        })
        f.view.attachPreview(f.preview) { oldReady++ }

        f.drawPreview()
        assertEquals(0, oldReady)
        assertEquals(0, newReady)
        f.drawPreview()
        assertEquals(1, newReady)
    }

    @Test
    fun `pending readiness survives a temporary window detach`() = withView { f ->
        var ready = 0
        f.view.attachPreview(f.preview) { ready++ }
        f.activity.setContentView(FrameLayout(f.activity))
        f.activity.setContentView(f.view)

        f.drawPreview()

        assertEquals(1, ready)
        assertTrue(f.view.isPreviewReady())
    }

    @Test
    fun `disposal during preview reparenting cannot attach it to the released view`() = withView { f ->
        val oldParent = FrameLayout(f.activity)
        oldParent.addView(f.preview)
        oldParent.setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
            override fun onChildViewAdded(parent: View?, child: View?) = Unit
            override fun onChildViewRemoved(parent: View?, child: View?) = f.view.release()
        })

        f.view.attachPreview(f.preview) { error("Released view must not become ready") }

        assertFalse(f.view.hasPreview())
        assertEquals(null, f.preview.parent)
    }

    @Test
    fun `disposal callback cannot trigger pending readiness during cleanup`() = withView { f ->
        var ready = 0
        f.view.attachPreview(f.preview) { ready++ }
        f.onDispose = { f.drawPreview() }

        f.view.dispose()

        assertEquals(0, ready)
    }

    private fun withView(block: (Fixture) -> Unit) {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val fixture = Fixture(activity.get())
        try {
            block(fixture)
        } finally {
            fixture.view.release()
            activity.pause().stop().destroy()
        }
    }

    private class Fixture(val activity: Activity) {
        val preview = View(activity)
        var disposeCount = 0
        var onDispose: () -> Unit = {}
        val view = ScannerView(activity, 42, { _, _, _ -> }) {
            disposeCount += 1
            onDispose()
        }

        init {
            activity.setContentView(view)
        }

        fun drawPreview(width: Int = 200, height: Int = 100) {
            preview.layout(0, 0, width, height)
            preview.viewTreeObserver.dispatchOnPreDraw()
        }
    }
}
