package com.dns_technologies.mlkit_scanner.scanner.components.camera.x

import android.graphics.Rect as AndroidRect
import android.util.Rational
import android.util.Size
import android.view.Surface
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraState
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.FocusMeteringResult
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.ZoomState
import androidx.camera.core.impl.StreamSpec
import androidx.camera.core.internal.ViewPorts
import com.dns_technologies.mlkit_scanner.CameraControlOperation
import com.dns_technologies.mlkit_scanner.PluginError
import com.dns_technologies.mlkit_scanner.scanner.components.camera.CameraAvailability
import com.dns_technologies.mlkit_scanner.scanner.components.camera.CameraFrame
import com.dns_technologies.mlkit_scanner.scanner.components.camera.Rect
import com.google.common.util.concurrent.ListenableFuture
import io.flutter.view.TextureRegistry
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyFloat
import org.mockito.Mockito.*
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowLooper

// region XCameraTest
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
internal class XCameraTest {
    @Test fun `unbound controls fail and disposed adapter cannot restart`() = withCameraFixture { f ->
        for (action in listOf<() -> Deferred<Unit>>(f.camera::resetFocus, { f.camera.focus(3000, 0F, 0F) },
            { f.camera.setZoomRatio(2F) }, { f.camera.setTorch(true) })) {
            assertSame(PluginError.CameraIsNotInitialized, runCatching(action).exceptionOrNull())
        }
        f.camera.dispose(); f.camera.dispose()
        assertSame(PluginError.CameraSessionDisposed, runCatching { f.start() }.exceptionOrNull())
        verify(f.producer).release()
    }
    @Test fun `device availability is independent from Flutter widget layout`() = withCameraFixture { f ->
        f.start()
        assertTrue(f.camera.isBound()); assertEquals(listOf(CameraAvailability.Open), f.availability)
        for (state in CameraState.Type.entries) {
            f.deviceState.value = CameraState.create(state)
            assertEquals(state == CameraState.Type.OPEN, f.availability.last() == CameraAvailability.Open)
        }
    }
    @Test fun `widget resizing never rebinds the camera use cases`() = withCameraFixture { f ->
        f.start(); f.layout(400, 800); f.layout(800, 400)
        assertEquals(1, f.groups.size); assertEquals(1, f.initialized)
        verify(f.provider, never()).unbindAll()
    }
    @Test fun `shared viewport preserves full preview and analysis frames at every rotation`() = withCameraFixture { f ->
        f.start()
        val group = f.groups.single()
        val viewport = requireNotNull(group.viewPort)
        val preview = group.useCases.filterIsInstance<Preview>().single()
        val analysis = group.useCases.filterIsInstance<ImageAnalysis>().single()
        val streams = mapOf(
            preview to StreamSpec.builder(Size(1920, 1080)).build(),
            analysis to StreamSpec.builder(Size(1280, 720)).build(),
        )

        for (rotation in listOf(0, 90, 180, 270)) {
            val crops = ViewPorts.calculateViewPortRects(
                AndroidRect(0, 0, 4080, 3060), false, viewport.aspectRatio,
                rotation, viewport.scaleType, viewport.layoutDirection, streams,
            )

            assertEquals("Preview crop at $rotation degrees", AndroidRect(0, 0, 1920, 1080), crops[preview])
            assertEquals("Analysis crop at $rotation degrees", AndroidRect(0, 0, 1280, 720), crops[analysis])
        }
    }
    @Test fun `surface restore after explicit pause cannot resurrect capture`() = withCameraFixture { f ->
        f.start(); f.surfaceCallback.onSurfaceCleanup(); f.camera.unbind()
        f.surfaceCallback.onSurfaceAvailable()
        assertFalse(f.camera.isBound()); assertEquals(1, f.groups.size)
    }
    @Test fun `surface restore resumes current binding with the same texture producer`() = withCameraFixture { f ->
        f.start(); f.surfaceCallback.onSurfaceCleanup(); f.surfaceCallback.onSurfaceAvailable()
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertTrue(f.camera.isBound()); assertEquals(2, f.groups.size)
        verify(f.producer, never()).release()
    }
    @Test fun `focus cover transform maps widget center and corners to rotated source`() {
        val center = XCamera.sourcePoint(.5F, .5F, 1280F, 720F, Size(400, 800), 90)
        assertEquals(640F, center.x, .01F); assertEquals(360F, center.y, .01F)
        val corner = XCamera.sourcePoint(0F, 0F, 1280F, 720F, Size(400, 800), 90)
        assertEquals(0F, corner.x, .01F); assertEquals(680F, corner.y, .01F)
    }
}
// endregion

// region XCameraBindingTest
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
internal class XCameraBindingTest {
    @Test
    fun `late provider completion after disposal does not bind or report error`() = withCameraFixture { f ->
        f.start(completeProvider = false)
        f.camera.dispose()
        f.future.complete(f.provider)
        ShadowLooper.idleMainLooper()

        assertTrue(f.groups.isEmpty())
        assertTrue(f.errors.isEmpty())
        assertFalse(f.future.isCancelled)
        verify(f.executor, never()).shutdown()
        verify(f.executor, never()).shutdownNow()
    }

    @Test
    fun `provider failure reports once without leaving a pending binding`() = withCameraFixture { f ->
        f.start(completeProvider = false)
        val failure = IllegalStateException("Provider unavailable")
        f.future.fail(failure)
        ShadowLooper.idleMainLooper()
        f.layout()

        assertFalse(f.camera.isBound())
        assertTrue(f.groups.isEmpty())
        assertEquals(1, f.errors.size)
        assertSame(failure, f.errors.single().cause)
    }

    @Test
    fun `failed bind permits a fresh start`() = withCameraFixture { f ->
        f.bindAction = { error("First attempt failed") }
        f.start()
        f.bindAction = { f.nativeCamera }
        f.start()

        assertTrue(f.camera.isBound())
        assertEquals(1, f.initialized)
        assertEquals(2, f.groups.size)
        assertEquals(1, f.errors.size)
    }

    @Test
    fun `dispose during provider initial bind releases the returned use cases`() = withCameraFixture { f ->
        f.bindAction = { f.camera.dispose(); f.nativeCamera }
        f.start()

        assertFalse(f.camera.isBound())
        assertEquals(0, f.initialized)
        verify(f.provider).unbind(*f.groups.single().useCases.toTypedArray())
    }

    @Test
    fun `device errors preserve code and cause even while already closed`() = withCameraFixture { f ->
        f.deviceState.value = CameraState.create(CameraState.Type.CLOSED)
        f.start()
        val cause = IllegalStateException("Device disconnected")
        val error = CameraState.StateError.create(CameraState.ERROR_CAMERA_FATAL_ERROR, cause)
        f.deviceState.value = CameraState.create(CameraState.Type.CLOSED, error)

        assertEquals(CameraAvailability.Closed(error.code, cause), f.availability.last())
    }

    @Test
    fun `disposing from availability callback does not initialize or leak observers`() = withCameraFixture { f ->
        f.onAvailability = { if (it == CameraAvailability.Open) f.camera.dispose() }
        f.start()

        assertFalse(f.camera.isBound())
        assertEquals(0, f.initialized)
        assertFalse(f.deviceState.hasObservers())
    }

    @Test
    fun `stream configuration error is reported even while device and preview remain open`() = withCameraFixture { f ->
        f.start()
        val cause = IllegalStateException("Stream configuration failed")
        val error = CameraState.StateError.create(CameraState.ERROR_STREAM_CONFIG, cause)
        f.deviceState.value = CameraState.create(CameraState.Type.OPEN, error)

        assertEquals(CameraAvailability.Closed(error.code, cause), f.availability.last())
    }

    @Test
    fun `throwing initialization callback cannot revive a disposed camera`() = withCameraFixture { f ->
        f.onInit = {
            f.camera.dispose()
            error("Caller failed after disposal")
        }
        f.start()

        assertSame(PluginError.CameraSessionDisposed, runCatching { f.start() }.exceptionOrNull())
        assertFalse(f.camera.isBound())
    }

    @Test
    fun `closed callback sees unbound state and can start another binding`() = withCameraFixture { f ->
        f.start()
        var boundDuringClose: Boolean? = null
        f.onAvailability = {
            if (it is CameraAvailability.Closed) {
                boundDuringClose = f.camera.isBound()
                f.onAvailability = {}
                f.start()
            }
        }

        f.camera.unbind()
        ShadowLooper.idleMainLooper()

        assertEquals(false, boundDuringClose)
        assertEquals(2, f.groups.size)
        assertTrue(f.camera.isBound())
    }

    @Test
    fun `availability observer failure releases binding and reports startup failure`() = withCameraFixture { f ->
        val error = IllegalStateException("Availability callback failed")
        f.onAvailability = { if (it == CameraAvailability.Open) throw error }
        f.start()

        assertFalse(f.camera.isBound())
        assertEquals(listOf(error), f.errors)
        assertFalse(f.deviceState.hasObservers())
    }

    @Test
    fun `old observer cannot change replacement binding when CameraX reuses camera`() = withCameraFixture { f ->
        f.start()
        val oldObserver = f.deviceState.observers.single()
        f.camera.unbind()
        f.start()
        assertEquals(2, f.groups.size)
        val before = f.availability.toList()

        oldObserver.onChanged(CameraState.create(CameraState.Type.CLOSED))

        assertEquals(before, f.availability)
    }

    @Test
    fun `failed bind cleans up only the use cases it attempted to bind`() = withCameraFixture { f ->
        f.bindAction = { throw IllegalArgumentException("Unsupported use case combination") }
        f.start()

        assertFalse(f.camera.isBound())
        assertEquals(1, f.errors.size)
        verify(f.provider).unbind(*f.groups.single().useCases.toTypedArray())
        verify(f.provider, never()).unbindAll()
    }

    @Test
    fun `late provider completion after unbind does not resurrect camera`() = withCameraFixture { f ->
        f.start(completeProvider = false)
        f.camera.unbind()
        f.future.complete(f.provider)
        ShadowLooper.idleMainLooper()

        assertFalse(f.camera.isBound())
        assertTrue(f.groups.isEmpty())
        assertEquals(0, f.initialized)
        assertTrue(f.errors.isEmpty())
        assertFalse(f.future.isCancelled)
    }

}
// endregion

// region XCameraControlsTest
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
internal class XCameraControlsTest {
    @Test
    fun `zoom rejects unavailable state and non-finite or out-of-range values`() = withCameraFixture { f ->
        f.start()
        assertSame(PluginError.CameraIsNotInitialized,
            runCatching { f.camera.setZoomRatio(2F) }.exceptionOrNull())
        setZoomBounds(f)
        for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 0.49F, 4.01F)) {
            assertSame(PluginError.InvalidArguments,
                runCatching { f.camera.setZoomRatio(value) }.exceptionOrNull())
        }
        verify(f.control, never()).setZoomRatio(anyFloat())
    }

    @Test
    fun `zoom accepts both device bounds and waits for hardware completion`() = withCameraFixture { f ->
        runBlocking {
            f.start()
            setZoomBounds(f)
            for (value in listOf(0.5F, 4F)) {
                val future = CameraTestFuture<Void?>()
                doReturn(future).`when`(f.control).setZoomRatio(value)
                val result = f.camera.setZoomRatio(value)
                assertFalse(result.isCompleted)
                future.complete(null)
                result.await()
                verify(f.control).setZoomRatio(value)
            }
        }
    }

    @Test
    fun `torch off without flash is no-op but torch on reports unsupported hardware`() = withCameraFixture { f ->
        runBlocking {
            f.start()
            f.camera.setTorch(false).await()
            assertSame(PluginError.DeviceHasNotFlash,
                runCatching { f.camera.setTorch(true) }.exceptionOrNull())
            verifyNoInteractions(f.control)
        }
    }

    @Test
    fun `torch forwards the requested state and preserves asynchronous failure`() = withCameraFixture { f ->
        runBlocking {
            f.start()
            doReturn(true).`when`(f.cameraInfo).hasFlashUnit()
            val future = CameraTestFuture<Void?>()
            doReturn(future).`when`(f.control).enableTorch(true)
            val result = f.camera.setTorch(true)
            assertFalse(result.isCompleted)
            val cause = IllegalStateException("Torch rejected")
            future.fail(cause)

            val error = runCatching { result.await() }.exceptionOrNull() as PluginError.CameraControlError
            assertEquals(CameraControlOperation.TORCH, error.operation)
            assertSame(cause, error.cause)
        }
    }

    @Test
    fun `focus rejects non-finite coordinates before metering`() = withCameraFixture { f ->
        runBlocking {
            f.start()
            for ((x, y) in listOf(Float.NaN to 0F, 0F to Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY to 0F)) {
                assertSame(PluginError.InvalidArguments,
                    runCatching { f.camera.focus(1000L, x, y) }.exceptionOrNull())
            }


            verifyNoInteractions(f.control)
        }
    }

    @Test
    fun `reset focus waits for CameraX and reports focus failure`() = withCameraFixture { f ->
        runBlocking {
            f.start()
            val future = CameraTestFuture<Void?>()
            doReturn(future).`when`(f.control).cancelFocusAndMetering()
            val result = f.camera.resetFocus()
            assertFalse(result.isCompleted)
            val cause = IllegalStateException("Camera closed")
            future.fail(cause)

            val error = runCatching { result.await() }.exceptionOrNull() as PluginError.CameraControlError
            assertEquals(CameraControlOperation.FOCUS, error.operation)
            assertSame(cause, error.cause)
        }
    }

    @Test
    fun `unbind rejects later controls but does not claim to cancel in-flight hardware operation`() = withCameraFixture { f ->
        runBlocking {
            f.start()
            val future = CameraTestFuture<Void?>()
            doReturn(future).`when`(f.control).cancelFocusAndMetering()
            val result = f.camera.resetFocus()
            f.camera.unbind()

            assertFalse(future.isCancelled)
            assertSame(PluginError.CameraIsNotInitialized,
                runCatching { f.camera.resetFocus() }.exceptionOrNull())
            future.complete(null)
            result.await()
        }
    }

    @Test
    fun `every control rejects calls after unbind and disposal`() = withCameraFixture { f ->
        f.start()
        val controls = listOf<() -> Deferred<Unit>>(
            f.camera::resetFocus,
            { f.camera.focus(3000L, 0F, 0F) },
            { f.camera.focus(3000L, Float.NaN, 0F) },
            { f.camera.setZoomRatio(2F) },
            { f.camera.setZoomRatio(Float.NaN) },
            { f.camera.setTorch(true) },
            { f.camera.setTorch(false) },
        )
        for (release in listOf(f.camera::unbind, f.camera::dispose)) {
            release()
            controls.forEach { control ->
                assertSame(PluginError.CameraIsNotInitialized, runCatching { control() }.exceptionOrNull())
            }
        }
        verifyNoInteractions(f.control)
    }

    private fun setZoomBounds(f: XCameraFixture) {
        val zoom = mock(ZoomState::class.java)
        doReturn(0.5F).`when`(zoom).minZoomRatio
        doReturn(4F).`when`(zoom).maxZoomRatio
        f.zoomState.value = zoom
    }
}
// endregion

// region XCameraControlCompletionTest
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
internal class XCameraControlCompletionTest {
    @Test
    fun `camera control deferred waits for future`() {
        val future = CameraTestFuture<Void?>()
        withResetFocus(future) { result ->
            assertFalse(result.isCompleted)
            future.complete(null)
            result.await()
            assertTrue(result.isCompleted)
        }
    }

    @Test
    fun `already completed future completes deferred immediately`() {
        val future = CameraTestFuture<Void?>()
        future.complete(null)
        withResetFocus(future) { result ->
            assertTrue(result.isCompleted)
            result.await()
        }
    }

    @Test
    fun `failure preserves operation and original cause without cancelling source`() {
        val future = CameraTestFuture<Void?>()
        withResetFocus(future) { result ->
            val cause = IllegalStateException("Camera rejected operation")
            future.fail(cause)

            val error = runCatching { result.await() }.exceptionOrNull() as PluginError.CameraControlError
            assertEquals(CameraControlOperation.FOCUS, error.operation)
            assertSame(cause, error.cause)
            assertNull(future.interruptionRequested)
        }
    }

    @Test
    fun `cancelling deferred requests non-interrupting future cancellation`() {
        val future = CameraTestFuture<Void?>()
        withResetFocus(future) { result ->
            result.cancel()

            assertTrue(future.isCancelled)
            assertEquals(false, future.interruptionRequested)
            assertTrue(runCatching { result.await() }.exceptionOrNull() is CancellationException)
        }
    }

    @Test
    fun `source cancellation is a camera failure not cancellation of the caller`() {
        val future = CameraTestFuture<Void?>()
        withResetFocus(future) { result ->
            future.cancel(false)

            val error = runCatching { result.await() }.exceptionOrNull() as PluginError.CameraControlError
            assertEquals(CameraControlOperation.FOCUS, error.operation)
            assertTrue(error.cause is CancellationException)
        }
    }

    @Test
    fun `cancelling one waiter does not cancel operation or another waiter`() {
        val future = CameraTestFuture<Void?>()
        withResetFocus(future) { result ->
            coroutineScope {
                val waiter = launch(start = CoroutineStart.UNDISPATCHED) { result.await() }
                waiter.cancel()
                waiter.join()

                assertFalse(result.isCompleted)
                assertFalse(future.isCancelled)
                future.complete(null)
                result.await()
            }
        }
    }

    @Test
    fun `listener registration failure is reported with operation context`() {
        val future = mock(ListenableFuture::class.java)
        val cause = IllegalStateException("Listener registration failed")
        doThrow(cause).`when`(future).addListener(any(Runnable::class.java), any(Executor::class.java))
        withResetFocus(future) { result ->
            val error = runCatching { result.await() }.exceptionOrNull() as PluginError.CameraControlError
            assertEquals(CameraControlOperation.FOCUS, error.operation)
            assertSame(cause, error.cause)
        }
    }

    @Test
    fun `CameraX cancellation is normalized without changing its original channel cause`() {
        val future = CameraTestFuture<Void?>()
        withResetFocus(future) { result ->
            val cause = IllegalStateException("Wrapper", CameraControl.OperationCanceledException("Camera closed"))
            future.fail(cause)

            val error = runCatching { result.await() }.exceptionOrNull() as PluginError.CameraControlError
            assertSame(cause, error.cause)
            assertSame(cause, error.contextualize(CameraControlOperation.ZOOM, 42).cause)
            assertEquals(setOf("operation", "viewId", "cause", "cameraStateErrorCode"), (error.details as Map<*, *>).keys)
        }
    }

    @Test
    fun `cyclic native failure preserves its original cause`() {
        val future = CameraTestFuture<Void?>()
        withResetFocus(future) { result ->
            val first = IllegalStateException("First")
            val second = IllegalStateException("Second", first)
            first.initCause(second)
            future.fail(first)

            val error = runCatching { result.await() }.exceptionOrNull() as PluginError.CameraControlError
            assertSame(first, error.cause)
        }
    }

    private fun withResetFocus(future: ListenableFuture<*>, block: suspend (Deferred<Unit>) -> Unit) =
        withCameraFixture { f ->
            f.start()
            doReturn(future).`when`(f.control).cancelFocusAndMetering()
            runBlocking { block(f.camera.resetFocus()) }
        }
}
// endregion

// region XCameraAnalyzerTest
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
internal class XCameraAnalyzerTest {
    @Test
    fun `frame callback borrows image until returning then it closes once`() = withCameraFixture { f ->
        val image = image()
        var borrowed: CameraFrame? = null
        f.onFrame = {
            borrowed = it
            verify(image, never()).close()
        }
        f.start()
        f.analyzer().analyze(image)

        val frame = requireNotNull(borrowed)
        verify(image).close()
        assertTrue(runCatching { frame.useNv21(null) { _, _, _, _ -> } }.isFailure)
        frame.close()
        verify(image).close()
    }

    @Test
    fun `frame callback may close early without double-close by adapter`() = withCameraFixture { f ->
        val image = image()
        f.onFrame = { it.close() }
        f.start()
        f.analyzer().analyze(image)
        verify(image).close()
    }

    @Test
    fun `callback exception still closes frame and does not break next frame`() = withCameraFixture { f ->
        val first = image()
        val second = image()
        var count = 0
        f.onFrame = {
            count++
            error("Consumer failed")
        }
        f.start()
        f.analyzer().analyze(first)
        f.analyzer().analyze(second)

        assertEquals(2, count)
        verify(first).close()
        verify(second).close()
        assertTrue(f.errors.isEmpty())
    }

    @Test
    fun `frame construction failure closes raw proxy without invoking consumer`() = withCameraFixture { f ->
        val image = image()
        var count = 0
        f.onFrame = { count++ }
        doThrow(IllegalStateException("Invalid metadata")).`when`(image).imageInfo
        f.start()
        f.analyzer().analyze(image)

        assertEquals(0, count)
        verify(image).close()
    }

    @Test
    fun `queued callback after unbind closes without constructing or delivering frame`() = withCameraFixture { f ->
        val image = image()
        var count = 0
        f.onFrame = { count++ }
        f.start()
        val queued = f.analyzer()
        f.camera.unbind()
        queued.analyze(image)

        assertEquals(0, count)
        verify(image).close()
        verify(image, never()).imageInfo
    }

    @Test
    fun `old analyzer cannot deliver into replacement binding`() = withCameraFixture { f ->
        val stale = image()
        val fresh = image()
        var count = 0
        f.onFrame = { count++ }
        f.start()
        val oldAnalyzer = f.analyzer()
        f.camera.unbind()
        f.start()
        oldAnalyzer.analyze(stale)
        f.analyzer().analyze(fresh)

        assertEquals(1, count)
        verify(stale).close()
        verify(stale, never()).imageInfo
        verify(fresh).close()
    }

    @Test
    fun `size-only layout updates crop used by next frame without rebinding`() = withCameraFixture { f ->
        val crops = mutableListOf<Rect>()
        f.onFrame = { crops += it.cropRect }
        f.start()
        f.layout(8, 6)
        f.analyzer().analyze(image())
        f.layout(1, 1)
        f.analyzer().analyze(image())

        assertEquals(listOf(
            Rect(0, 0, 8, 6),
            Rect(1, 0, 7, 6),
        ), crops)
        assertEquals(1, f.groups.size)
    }

    @Test
    fun `already admitted frame completes its scope across disposal while later callbacks are rejected`() = withCameraFixture { f ->
        val active = image()
        val late = image()
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        f.onFrame = {
            entered.countDown()
            assertTrue(finish.await(5, TimeUnit.SECONDS))
            assertEquals(8, it.width)
        }
        f.start()
        val analyzer = f.analyzer()
        try {
            val result = executor.submit { analyzer.analyze(active) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            f.camera.dispose()
            verify(active, never()).close()
            finish.countDown()
            result.get(5, TimeUnit.SECONDS)
            analyzer.analyze(late)

            verify(active).close()
            verify(late).close()
            verify(late, never()).imageInfo
        } finally {
            finish.countDown()
            executor.shutdownNow()
        }
    }

    private fun image(): ImageProxy {
        val image = mock(ImageProxy::class.java)
        val info = mock(ImageInfo::class.java)
        doReturn(info).`when`(image).imageInfo
        doReturn(8).`when`(image).width
        doReturn(6).`when`(image).height
        doReturn(AndroidRect(0, 0, 8, 6)).`when`(image).cropRect
        return image
    }
}
// endregion
