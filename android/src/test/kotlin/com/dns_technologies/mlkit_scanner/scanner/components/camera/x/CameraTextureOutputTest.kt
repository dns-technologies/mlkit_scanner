package com.dns_technologies.mlkit_scanner.scanner.components.camera.x

import android.view.Surface
import com.dns_technologies.mlkit_scanner.scanner.components.camera.CameraPreviewDescription
import com.dns_technologies.mlkit_scanner.scanner.components.camera.CameraPreviewState
import io.flutter.view.TextureRegistry
import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
internal class CameraTextureOutputTest {
    @Test fun `preview callback failure during disposal still releases producer and completes disposal`() {
        val f = Fixture()
        val failure = IllegalStateException("Preview channel detached")
        f.onPublish = { throw failure }

        val error = runCatching { f.output.dispose() }.exceptionOrNull()

        assertSame(failure, error)
        assertTrue(f.output.disposal.isCompleted)
        verify(f.producer).release()
        f.output.dispose()
        verify(f.producer, times(1)).release()
    }
    @Test fun `callback removal failure still invalidates surface and waits for its return`() {
        val f = Fixture()
        val request = f.request()
        f.output.onSurfaceRequested(request.request)
        val failure = IllegalStateException("Callback removal failed")
        doThrow(failure).`when`(f.producer).setCallback(null)

        val error = runCatching { f.output.dispose() }.exceptionOrNull()

        assertSame(failure, error)
        assertFalse(f.output.disposal.isCompleted)
        verify(request.request).invalidate()
        assertNull(f.events.last())
        request.finish()
        assertTrue(f.output.disposal.isCompleted)
        verify(f.producer).release()
    }
    @Test fun `unopened or paused-before-first-frame output never advertises an image`() {
        val f = Fixture(); val request = f.request()
        f.output.onSurfaceRequested(request.request)
        f.output.cameraAvailable(false); f.output.pause()
        assertTrue(f.events.filterNotNull().all { it.state == CameraPreviewState.Starting })
        f.output.dispose(); request.finish()
    }
    @Test fun `disposal waits for every provided request including replaced ones`() {
        val f = Fixture(); val first = f.request(); val second = f.request()
        f.output.onSurfaceRequested(first.request); f.output.onSurfaceRequested(second.request)
        f.output.dispose()
        assertFalse(f.output.disposal.isCompleted); verify(f.producer, never()).release()
        second.finish(); assertFalse(f.output.disposal.isCompleted)
        first.finish(); assertTrue(f.output.disposal.isCompleted); verify(f.producer).release()
        f.output.dispose(); verify(f.producer, times(1)).release()
    }
    @Test fun `old request completion and camera frame cannot clear replacement output`() {
        val f = Fixture(); val first = f.request(); val second = f.request()
        f.output.onSurfaceRequested(first.request)
        f.output.cameraAvailable(true)
        val frame = f.output.awaitedFrame!!
        f.output.onSurfaceRequested(second.request); first.finish()
        f.output.frameCaptured(frame)
        assertEquals(CameraPreviewState.Starting, f.events.last()!!.state)
        f.output.frameCaptured(f.output.awaitedFrame!!)
        assertEquals(CameraPreviewState.Streaming, f.events.last()!!.state)
        f.output.cameraAvailable(false)
        assertEquals(CameraPreviewState.Paused, f.events.last()!!.state)
        f.output.dispose(); second.finish()
    }
    @Test fun `closed camera invalidates pending confirmation even when it reopens on same surface`() {
        val f = Fixture()
        val request = f.request()
        f.output.onSurfaceRequested(request.request)
        f.output.cameraAvailable(true)
        val beforeClose = requireNotNull(f.output.awaitedFrame)

        f.output.cameraAvailable(false)
        assertNull(f.output.awaitedFrame)
        f.output.cameraAvailable(true)
        f.output.frameCaptured(beforeClose)

        assertEquals(CameraPreviewState.Starting, f.events.last()?.state)
        f.output.frameCaptured(requireNotNull(f.output.awaitedFrame))
        assertEquals(CameraPreviewState.Streaming, f.events.last()?.state)
        assertNull(f.output.awaitedFrame)
        f.output.dispose()
        request.finish()
    }
    @Test fun `size precedes surface acquisition and manual transformation includes source crop`() {
        val f = Fixture(); val request = f.request()
        f.output.onSurfaceRequested(request.request)
        inOrder(f.producer, request.request).apply {
            verify(f.producer).setSize(1280, 720)
            verify(f.producer).surface
            verify(request.request).provideSurface(anyValue(), anyValue(), anyValue())
        }
        assertEquals(90, f.events.last()!!.rotationDegrees)
        assertEquals(160, f.events.last()!!.cropRect.left)
        assertEquals(960, f.events.last()!!.cropRect.width)
        f.output.dispose(); request.finish()
    }
    @Test fun `producer transformation publishes rotated full bounds without double rotation`() {
        val f = Fixture()
        val request = f.request()
        doReturn(true).`when`(f.producer).handlesCropAndRotation()

        f.output.onSurfaceRequested(request.request)

        val preview = f.events.last()!!
        assertEquals(720, preview.width)
        assertEquals(1280, preview.height)
        assertEquals(0, preview.rotationDegrees)
        assertEquals(com.dns_technologies.mlkit_scanner.scanner.components.camera.Rect(0, 0, 720, 1280), preview.cropRect)
        f.output.dispose()
        request.finish()
    }
    private class Fixture {
        val producer = mock(TextureRegistry.SurfaceProducer::class.java)
        val events = mutableListOf<CameraPreviewDescription?>()
        var onPublish: () -> Unit = {}
        val output: CameraTextureOutput
        init {
            doReturn(mock(Surface::class.java)).`when`(producer).surface
            output = CameraTextureOutput(producer, Executor { it.run() }) { events += it; onPublish() }
        }
        fun request() = PreviewSurfaceRequest()
    }
    companion object { fun <T> anyValue(): T = any<T>() }
}
