package com.dns_technologies.mlkit_scanner.scanner.components.camera.x

import android.util.Rational
import android.view.Surface
import androidx.camera.core.CameraState
import androidx.camera.core.ImageAnalysis
import androidx.camera.view.PreviewView
import com.dns_technologies.mlkit_scanner.PluginError
import com.dns_technologies.mlkit_scanner.scanner.components.camera.CameraAvailability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
internal class XCameraBindingTest {
    @Test
    fun `binding waits for viewport and duplicate starts do not bind twice`() = withCameraFixture { f ->
        doReturn(null).`when`(f.preview).viewPort
        f.start()
        f.start()
        assertTrue(f.groups.isEmpty())
        assertFalse(f.camera.isBound())

        doReturn(XCameraFixture.viewPort()).`when`(f.preview).viewPort
        f.layout()
        f.start()
        assertTrue(f.camera.isBound())
        assertEquals(1, f.initialized)
        assertEquals(1, f.groups.size)
        assertEquals(2, f.groups.single().useCases.size)
        assertEquals(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST,
            f.groups.single().useCases.filterIsInstance<ImageAnalysis>().single().backpressureStrategy)
    }

    @Test
    fun `late provider completion after disposal does not bind or report error`() = withCameraFixture { f ->
        f.start(completeProvider = false)
        f.camera.dispose()
        f.future.complete(f.provider)
        ShadowLooper.idleMainLooper()

        assertTrue(f.groups.isEmpty())
        assertTrue(f.errors.isEmpty())
        assertFalse(f.future.isCancelled)
        verify(f.preview).removeOnLayoutChangeListener(f.layoutListener)
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
    fun `layout updates shared viewport without replacing or unbinding use cases`() = withCameraFixture { f ->
        f.start()
        val original = f.groups.single()
        doReturn(XCameraFixture.viewPort(aspect = Rational(2, 1))).`when`(f.preview).viewPort
        f.layout(200, 100)
        f.layout(200, 100)

        assertEquals(2, f.groups.size)
        assertEquals(Rational(2, 1), f.groups.last().viewPort?.aspectRatio)
        assertEquals(original.useCases, f.groups.last().useCases)
        assertEquals(listOf(CameraAvailability.Open), f.availability)
        verify(f.provider, never()).unbind(*original.useCases.toTypedArray())
    }

    @Test
    fun `rotation keeps camera controls analyzer and availability on the same use cases`() = withCameraFixture { f ->
        f.start()
        val original = f.groups.single()
        val analyzer = f.analyzer()
        f.rotate()

        assertTrue(f.camera.isBound())
        assertEquals(1, f.initialized)
        assertEquals(Surface.ROTATION_90, f.groups.last().viewPort?.rotation)
        assertEquals(original.useCases, f.groups.last().useCases)
        assertEquals(Surface.ROTATION_90,
            original.useCases.filterIsInstance<ImageAnalysis>().single().targetRotation)
        assertSame(analyzer, f.analyzer())
        assertEquals(listOf(CameraAvailability.Open), f.availability)
        verify(f.provider, never()).unbind(*original.useCases.toTypedArray())
        verify(f.provider, never()).unbindAll()
    }

    @Test
    fun `viewport failure reports original cause and releases owned resources without rollback`() = withCameraFixture { f ->
        f.start()
        val original = f.groups.single()
        val failure = IllegalArgumentException("Cannot update viewport")
        f.bindAction = { throw failure }
        f.rotate()

        assertFalse(f.camera.isBound())
        assertEquals(listOf(failure), f.errors)
        assertEquals(2, f.groups.size)
        assertFalse(f.deviceState.hasObservers())
        assertFalse(f.streamState.hasObservers())
        verify(f.provider).unbind(*original.useCases.toTypedArray())
    }

    @Test
    fun `dispose during viewport update cannot revive camera or leak use cases`() = withCameraFixture { f ->
        f.start()
        f.bindAction = { f.camera.dispose(); f.nativeCamera }
        f.rotate()

        assertFalse(f.camera.isBound())
        assertFalse(f.deviceState.hasObservers())
        assertFalse(f.streamState.hasObservers())
        assertEquals(2, f.groups.size)
        // Disposal unbinds once; the returning SDK call is also cleaned up.
        verify(f.provider, org.mockito.Mockito.times(2)).unbind(*f.groups.last().useCases.toTypedArray())
        assertSame(PluginError.CameraSessionDisposed, runCatching { f.start() }.exceptionOrNull())
    }

    @Test
    fun `dispose during failed viewport update does not retry or report stale error`() = withCameraFixture { f ->
        f.start()
        f.bindAction = { f.camera.dispose(); error("Update stopped") }
        f.rotate()

        assertEquals(2, f.groups.size)
        assertFalse(f.camera.isBound())
        assertTrue(f.errors.isEmpty())
        verify(f.provider).unbind(*f.groups.last().useCases.toTypedArray())
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
    fun `reentrant layout during initial binding or viewport update does not bind twice`() = withCameraFixture { f ->
        f.bindAction = { f.layout(); f.nativeCamera }
        f.start()
        assertEquals(1, f.groups.size)
        f.rotate()

        assertTrue(f.camera.isBound())
        assertEquals(2, f.groups.size)
        assertEquals(1, f.initialized)
    }

    @Test
    fun `availability waits for device and preview then suppresses repeated ordinary states`() = withCameraFixture { f ->
        f.deviceState.value = CameraState.create(CameraState.Type.OPENING)
        f.streamState.value = PreviewView.StreamState.IDLE
        f.start()
        assertEquals(1, f.initialized)
        assertTrue(f.availability.isEmpty())

        f.deviceState.value = CameraState.create(CameraState.Type.OPEN)
        assertTrue(f.availability.isEmpty())
        f.streamState.value = PreviewView.StreamState.STREAMING
        f.deviceState.value = CameraState.create(CameraState.Type.OPEN)
        f.streamState.value = PreviewView.StreamState.STREAMING
        assertEquals(listOf(CameraAvailability.Open), f.availability)

        f.streamState.value = PreviewView.StreamState.IDLE
        f.deviceState.value = CameraState.create(CameraState.Type.CLOSED)
        assertEquals(listOf(CameraAvailability.Open, CameraAvailability.Closed()), f.availability)
    }

    @Test
    fun `device errors preserve code and cause even while already closed`() = withCameraFixture { f ->
        f.deviceState.value = CameraState.create(CameraState.Type.CLOSED)
        f.start()
        val cause = IllegalStateException("Device disconnected")
        val error = CameraState.StateError.create(CameraState.ERROR_CAMERA_FATAL_ERROR, cause)
        f.deviceState.value = CameraState.create(CameraState.Type.CLOSED, error)

        assertEquals(listOf(CameraAvailability.Closed(error.code, cause)), f.availability)
    }

    @Test
    fun `disposing from availability callback does not initialize or leak observers`() = withCameraFixture { f ->
        f.onAvailability = { if (it == CameraAvailability.Open) f.camera.dispose() }
        f.start()

        assertFalse(f.camera.isBound())
        assertEquals(0, f.initialized)
        assertFalse(f.deviceState.hasObservers())
        assertFalse(f.streamState.hasObservers())
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
        assertFalse(f.streamState.hasObservers())
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
