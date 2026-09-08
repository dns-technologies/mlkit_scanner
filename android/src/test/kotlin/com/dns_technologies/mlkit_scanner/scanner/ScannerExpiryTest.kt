package com.dns_technologies.mlkit_scanner.scanner

import android.os.Handler
import android.os.Looper
import android.view.View
import com.dns_technologies.mlkit_scanner.scanner.components.analyzer.ImageBarcodeAnalyzer
import com.dns_technologies.mlkit_scanner.scanner.components.camera.Camera
import java.time.Duration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
internal class ScannerExpiryTest {
    @Test
    fun `release disposes SDK resources exactly after 300 ms`() {
        val f = Fixture()
        f.scanner.select(f.first)
        f.scanner.releaseCamera()
        assertNull(f.scanner.viewId)
        verify(f.first).detachPreview()

        f.advance(299)
        assertFalse(f.scanner.isDisposed)
        verify(f.camera, never()).dispose()
        f.advance(1)

        assertTrue(f.scanner.isDisposed)
        verify(f.camera).dispose()
        verify(f.analyzer).dispose()
        assertEquals(1, f.releases)
    }

    @Test
    fun `reattachment cancels cleanup and subsequent release starts a fresh grace period`() {
        val f = Fixture()
        f.scanner.select(f.first)
        f.scanner.releaseCamera()
        f.advance(299)
        f.scanner.select(f.second)
        f.advance(301)
        assertEquals(43, f.scanner.viewId)
        assertFalse(f.scanner.isDisposed)

        f.scanner.releaseCamera()
        f.advance(299)
        assertFalse(f.scanner.isDisposed)
        f.advance(1)
        assertTrue(f.scanner.isDisposed)
        assertEquals(1, f.releases)
    }

    @Test
    fun `repeated release does not extend the idle deadline`() {
        val f = Fixture()
        f.scanner.select(f.first)
        f.scanner.releaseCamera()
        f.advance(200)
        f.scanner.releaseCamera()
        f.advance(100)
        assertTrue(f.scanner.isDisposed)
        assertEquals(1, f.releases)
    }

    @Test
    fun `invalid view cannot cancel pending cleanup`() {
        val f = Fixture()
        f.scanner.select(f.first)
        f.scanner.releaseCamera()
        f.advance(200)
        doReturn(true).`when`(f.second).isDisposed
        f.scanner.select(f.second)
        f.advance(100)
        assertTrue(f.scanner.isDisposed)
    }

    @Test
    fun `failed detach still schedules terminal cleanup`() {
        val f = Fixture()
        f.scanner.select(f.first)
        doThrow(IllegalStateException("detach failed")).`when`(f.first).detachPreview()
        assertNotNull(runCatching { f.scanner.releaseCamera() }.exceptionOrNull())
        f.advance(300)
        assertTrue(f.scanner.isDisposed)
        verify(f.camera).dispose()
        verify(f.analyzer).dispose()
    }

    @Test
    fun `terminal teardown cancels pending idle cleanup`() {
        val f = Fixture()
        f.scanner.select(f.first)
        f.scanner.releaseCamera()
        f.scanner.dispose()
        f.advance(1000)
        verify(f.camera).dispose()
        verify(f.analyzer).dispose()
        assertEquals(1, f.releases)
    }

    @Test
    fun `selecting a disposed view leaves the current preview attached`() {
        val f = Fixture()
        f.scanner.select(f.first)
        doReturn(true).`when`(f.second).isDisposed

        f.scanner.select(f.second)

        assertEquals(42, f.scanner.viewId)
        verify(f.first, never()).detachPreview()
        f.scanner.dispose()
        assertNull(f.scanner.viewId)
        verify(f.first).detachPreview()
    }

    private class Fixture {
        val camera = mock(Camera::class.java)
        val analyzer = mock(ImageBarcodeAnalyzer::class.java)
        val first = mock(ScannerView::class.java)
        val second = mock(ScannerView::class.java)
        var releases = 0
        val scanner: Scanner

        init {
            doReturn(mock(View::class.java)).`when`(camera).previewView
            doReturn(42).`when`(first).viewId
            doReturn(43).`when`(second).viewId
            scanner = Scanner(camera, analyzer, Handler(Looper.getMainLooper()), { _, _ -> releases++ })
        }

        fun advance(milliseconds: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(milliseconds))
    }
}
