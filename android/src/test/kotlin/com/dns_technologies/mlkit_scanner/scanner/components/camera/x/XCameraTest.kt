package com.dns_technologies.mlkit_scanner.scanner.components.camera.x

import androidx.camera.core.CameraState
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import com.dns_technologies.mlkit_scanner.PluginError
import com.dns_technologies.mlkit_scanner.scanner.components.camera.CameraCommand
import java.util.concurrent.ExecutorService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
internal class XCameraTest {
    @Test
    fun `controls reject commands before a camera is bound`() {
        val camera = XCamera(RuntimeEnvironment.getApplication())

        for (command in listOf(
            CameraCommand.ResetFocus,
            CameraCommand.Focus(3000L, 0F, 0F),
            CameraCommand.SetZoomRatio(2F),
            CameraCommand.SetTorch(true),
        )) {
            val error = runCatching { camera.execute(command) }.exceptionOrNull()
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
    fun `hide preview preserves its last rendered texture opacity`() {
        val camera = XCamera(RuntimeEnvironment.getApplication())
        camera.previewView.alpha = 0.35F

        camera.hidePreview()

        assertEquals(0.35F, camera.previewView.alpha, 0F)
        camera.dispose()
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
    fun `camera controls become ready only for open device and streaming preview`() {
        assertTrue(
            isCameraReadyForControls(
                CameraState.Type.OPEN,
                PreviewView.StreamState.STREAMING,
            ),
        )
        assertFalse(
            isCameraReadyForControls(
                CameraState.Type.OPEN,
                PreviewView.StreamState.IDLE,
            ),
        )
        assertFalse(
            isCameraReadyForControls(
                CameraState.Type.OPENING,
                PreviewView.StreamState.STREAMING,
            ),
        )
        assertFalse(isCameraReadyForControls(null, null))
    }
}
