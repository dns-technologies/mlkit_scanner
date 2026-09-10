package com.dns_technologies.mlkit_scanner.scanner.components.camera.x

import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.FocusMeteringResult
import androidx.camera.core.ZoomState
import com.dns_technologies.mlkit_scanner.CameraControlOperation
import com.dns_technologies.mlkit_scanner.PluginError
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyFloat
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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
    fun `focus uses preview coordinates without centering and applies reset duration`() = withCameraFixture { f ->
        runBlocking {
            f.start()
            val actions = mutableListOf<FocusMeteringAction>()
            val future = CameraTestFuture<FocusMeteringResult>()
            doAnswer {
                actions += it.getArgument<FocusMeteringAction>(0)
                future
            }.`when`(f.control).startFocusAndMetering(XCameraFixture.anyValue())

            val result = f.camera.focus(3000L, 75F, 50F)
            val action = actions.single()
            assertEquals(0.75F, action.meteringPointsAf.single().x, 0F)
            assertEquals(0.25F, action.meteringPointsAf.single().y, 0F)
            assertEquals(3000L, action.autoCancelDurationInMillis)
            assertTrue(action.isAutoCancelEnabled)
            assertFalse(result.isCompleted)
            future.complete(mock(FocusMeteringResult::class.java))
            result.await()

            for (delay in listOf(0L, -1L)) {
                f.camera.focus(delay, 0F, 0F).await()
                assertFalse(actions.last().isAutoCancelEnabled)
                assertEquals(0F, actions.last().meteringPointsAf.single().x, 0F)
                assertEquals(0F, actions.last().meteringPointsAf.single().y, 0F)
            }
        }
    }

    @Test
    fun `focus rejects non-finite coordinates and empty preview is no-op`() = withCameraFixture { f ->
        runBlocking {
            f.start()
            for ((x, y) in listOf(Float.NaN to 0F, 0F to Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY to 0F)) {
                assertSame(PluginError.InvalidArguments,
                    runCatching { f.camera.focus(1000L, x, y) }.exceptionOrNull())
            }
            doReturn(0).`when`(f.preview).width
            f.camera.focus(1000L, 0F, 0F).await()
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
