package com.dns_technologies.mlkit_scanner.scanner.components.camera.x

import android.graphics.Rect
import android.util.Size
import android.view.Surface
import androidx.camera.core.SurfaceRequest
import androidx.core.util.Consumer
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
    @Test fun `unopened or paused-before-first-frame output never advertises an image`() {
        val f = Fixture(); val request = f.request()
        f.output.onSurfaceRequested(request.request)
        f.output.cameraAvailable(false); f.output.pause()
        assertTrue(f.events.filterNotNull().all { it["state"] == "starting" })
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
        val frame = f.output.frameStarted(100)!!
        f.output.onSurfaceRequested(second.request); first.finish()
        f.output.frameCaptured(frame)
        assertEquals("starting", f.events.last()!!["state"])
        f.output.frameCaptured(f.output.frameStarted(101)!!)
        assertEquals("streaming", f.events.last()!!["state"])
        f.output.cameraAvailable(false)
        assertEquals("paused", f.events.last()!!["state"])
        f.output.dispose(); second.finish()
    }
    @Test fun `size precedes surface acquisition and manual transformation includes source crop`() {
        val f = Fixture(); val request = f.request()
        f.output.onSurfaceRequested(request.request)
        inOrder(f.producer, request.request).apply {
            verify(f.producer).setSize(1280, 720)
            verify(f.producer).surface
            verify(request.request).provideSurface(anyValue(), anyValue(), anyValue())
        }
        assertEquals(90, f.events.last()!!["rotationDegrees"])
        assertEquals(160, f.events.last()!!["cropLeft"])
        assertEquals(960, f.events.last()!!["cropWidth"])
        f.output.dispose(); request.finish()
    }
    private class Fixture {
        val producer = mock(TextureRegistry.SurfaceProducer::class.java)
        val events = mutableListOf<Map<String, Any>?>()
        val output: CameraTextureOutput
        init { doReturn(mock(Surface::class.java)).`when`(producer).surface; output = CameraTextureOutput(producer, Executor { it.run() }, events::add) }
        fun request() = Request()
    }
    private class Request {
        val request = mock(SurfaceRequest::class.java)
        lateinit var completion: Consumer<SurfaceRequest.Result>
        init {
            doReturn(Size(1280, 720)).`when`(request).resolution
            val info = mock(SurfaceRequest.TransformationInfo::class.java)
            doReturn(90).`when`(info).rotationDegrees
            doReturn(Rect(160, 0, 1120, 720)).`when`(info).cropRect
            doAnswer { it.getArgument<SurfaceRequest.TransformationInfoListener>(1).onTransformationInfoUpdate(info); null }.`when`(request)
                .setTransformationInfoListener(anyValue(), anyValue())
            doAnswer { completion = it.getArgument(2); null }.`when`(request).provideSurface(anyValue(), anyValue(), anyValue())
        }
        fun finish() { completion.accept(mock(SurfaceRequest.Result::class.java)) }
    }
    companion object { fun <T> anyValue(): T = any<T>() }
}
