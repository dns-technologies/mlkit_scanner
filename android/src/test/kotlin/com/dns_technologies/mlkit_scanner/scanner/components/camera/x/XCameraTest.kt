package com.dns_technologies.mlkit_scanner.scanner.components.camera.x

import android.widget.FrameLayout
import androidx.camera.core.CameraState
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.MutableLiveData
import com.dns_technologies.mlkit_scanner.PluginError
import com.dns_technologies.mlkit_scanner.scanner.components.camera.CameraAvailability
import java.util.concurrent.ExecutorService
import kotlinx.coroutines.Deferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
internal class XCameraTest {
    @Test
    fun `controls reject calls before a camera is bound`() {
        val camera = XCamera(RuntimeEnvironment.getApplication())

        for (control in listOf<() -> Deferred<Unit>>(
            camera::resetFocus,
            { camera.focus(3000L, 0F, 0F) },
            { camera.setZoomRatio(2F) },
            { camera.setTorch(true) },
            { camera.setTorch(false) },
        )) {
            val error = runCatching { control() }.exceptionOrNull()
            assertSame(PluginError.CameraIsNotInitialized, error)
        }
        assertSame(
            PluginError.CameraIsNotInitialized,
            runCatching { camera.showPreview() }.exceptionOrNull(),
        )
        assertFalse(camera.isBound())
        camera.dispose()
    }

    @Test
    fun `hide preview keeps texture attached but invisible during configuration`() {
        val context = RuntimeEnvironment.getApplication()
        val camera = XCamera(context)
        val container = FrameLayout(context)
        container.addView(camera.previewView)
        camera.previewView.layout(0, 0, 100, 200)
        camera.previewView.alpha = 0.35F

        camera.hidePreview()

        assertEquals(0F, camera.previewView.alpha, 0F)
        assertEquals(android.view.View.VISIBLE, camera.previewView.visibility)
        assertSame(container, camera.previewView.parent)
        assertEquals(100, camera.previewView.width)
        assertEquals(200, camera.previewView.height)
        camera.dispose()
    }

    @Test
    fun `hide and show only change transparency without rebinding the camera`() = withCameraFixture { f ->
        f.start()
        clearInvocations(f.provider, f.preview)

        f.camera.hidePreview()
        f.camera.hidePreview()
        f.camera.showPreview()
        f.camera.hidePreview()

        inOrder(f.preview).apply {
            verify(f.preview, org.mockito.Mockito.times(2)).alpha = 0F
            verify(f.preview).alpha = 1F
            verify(f.preview).alpha = 0F
        }
        assertTrue(f.camera.isBound())
        assertEquals(CameraAvailability.Open, f.availability.last())
        verifyNoInteractions(f.provider)
    }

    @Test
    fun `dispose is idempotent and prevents a later bind`() {
        val camera = XCamera(RuntimeEnvironment.getApplication())
        camera.dispose()
        camera.dispose()

        val error = runCatching {
            camera.bind(
                lifecycleOwner = mock(LifecycleOwner::class.java),
                analysisExecutor = mock(ExecutorService::class.java),
                onFrame = {},
                onAvailabilityChanged = {},
                onInit = {},
                onError = {},
            )
        }.exceptionOrNull()

        assertSame(PluginError.CameraSessionDisposed, error)
        assertFalse(camera.isBound())
    }

    @Test
    fun `camera controls become ready only for open device and streaming preview`() = withCameraFixture { f ->
        f.start()
        for (device in CameraState.Type.entries) {
            for (stream in PreviewView.StreamState.entries) {
                f.deviceState.value = CameraState.create(device)
                f.streamState.value = stream
                val expectedOpen = device == CameraState.Type.OPEN && stream == PreviewView.StreamState.STREAMING
                assertEquals("device=$device, stream=$stream", expectedOpen,
                    f.availability.last() == CameraAvailability.Open)
            }
        }
    }

    @Test
    fun `readiness waits for both initial device and preview states in either order`() {
        for (deviceFirst in listOf(true, false)) withCameraFixture { f ->
            val device = MutableLiveData<CameraState>()
            val stream = MutableLiveData<PreviewView.StreamState>()
            doReturn(device).`when`(f.cameraInfo).cameraState
            doReturn(stream).`when`(f.preview).previewStreamState
            f.start()
            assertEquals(emptyList<CameraAvailability>(), f.availability)

            if (deviceFirst) device.value = CameraState.create(CameraState.Type.OPEN)
            else stream.value = PreviewView.StreamState.STREAMING
            assertEquals(emptyList<CameraAvailability>(), f.availability)

            if (deviceFirst) stream.value = PreviewView.StreamState.STREAMING
            else device.value = CameraState.create(CameraState.Type.OPEN)
            assertEquals(listOf(CameraAvailability.Open), f.availability)
        }
    }
}
