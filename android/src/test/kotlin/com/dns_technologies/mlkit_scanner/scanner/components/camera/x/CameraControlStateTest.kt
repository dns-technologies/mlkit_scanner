package com.dns_technologies.mlkit_scanner.scanner.components.camera.x

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal class CameraControlStateTest {
    @Test
    fun `successful controls are retained and restored after camera reopens`() {
        val state = CameraControlState()
        assertTrue(state.onCameraOpened())
        val zoom = state.beginZoom(0.75F)
        val torch = state.beginTorchToggle(currentTorchEnabled = false)

        state.completeZoom(zoom, succeeded = true)
        state.completeTorch(torch, succeeded = true)
        state.onCameraUnavailable()
        assertTrue(state.onCameraOpened())

        assertEquals(0.75F, state.beginZoomRestoration(0.0F)?.value)
        assertEquals(true, state.beginTorchRestoration(false)?.value)
    }

    @Test
    fun `matching controls and duplicate open event do not start restoration`() {
        val state = CameraControlState()
        state.onCameraOpened()
        state.completeZoom(state.beginZoom(0.5F), succeeded = true)
        state.completeTorch(
            state.beginTorchToggle(currentTorchEnabled = false),
            succeeded = true,
        )

        assertFalse(state.onCameraOpened())
        assertNull(state.beginZoomRestoration(0.5F))
        assertNull(state.beginTorchRestoration(true))
    }

    @Test
    fun `new camera opening can force controls despite stale CameraInfo values`() {
        val state = CameraControlState()
        state.onCameraOpened()
        state.completeZoom(state.beginZoom(0.5F), succeeded = true)
        state.completeTorch(
            state.beginTorchToggle(currentTorchEnabled = false),
            succeeded = true,
        )
        state.onCameraUnavailable()
        state.onCameraOpened()

        assertEquals(0.5F, state.beginZoomRestoration(0.5F, force = true)?.value)
        assertEquals(true, state.beginTorchRestoration(true, force = true)?.value)
    }

    @Test
    fun `failed user controls preserve last confirmed values`() {
        val state = CameraControlState()
        state.onCameraOpened()
        state.completeZoom(state.beginZoom(0.25F), succeeded = true)
        state.completeTorch(
            state.beginTorchToggle(currentTorchEnabled = false),
            succeeded = true,
        )

        val zoomCompletion = state.completeZoom(state.beginZoom(0.75F), succeeded = false)
        val torchCompletion = state.completeTorch(
            state.beginTorchToggle(currentTorchEnabled = true),
            succeeded = false,
        )

        assertTrue(zoomCompletion.shouldRestore)
        assertTrue(torchCompletion.shouldRestore)
        assertEquals(0.25F, state.beginZoomRestoration(0.0F)?.value)
        assertEquals(true, state.beginTorchRestoration(false)?.value)
    }

    @Test
    fun `stale successful operation cannot overwrite a newer request`() {
        val state = CameraControlState()
        state.onCameraOpened()
        val first = state.beginZoom(0.25F)
        val second = state.beginZoom(0.75F)

        val staleCompletion = state.completeZoom(first, succeeded = true)
        state.completeZoom(second, succeeded = true)

        assertFalse(staleCompletion.shouldRestore)
        assertEquals(0.75F, state.beginZoomRestoration(0.0F)?.value)
    }

    @Test
    fun `user operation supersedes pending restoration`() {
        val state = CameraControlState()
        state.onCameraOpened()
        state.completeZoom(state.beginZoom(0.5F), succeeded = true)
        state.onCameraUnavailable()
        state.onCameraOpened()
        val restoration = requireNotNull(state.beginZoomRestoration(0.0F))

        val userOperation = state.beginZoom(0.75F)
        val staleRestoration = state.completeZoom(restoration, succeeded = false)
        state.completeZoom(userOperation, succeeded = true)

        assertFalse(staleRestoration.restorationFailed)
        assertEquals(0.75F, state.beginZoomRestoration(0.0F)?.value)
    }

    @Test
    fun `current restoration failure is reported without changing retained value`() {
        val state = CameraControlState()
        state.onCameraOpened()
        state.completeZoom(state.beginZoom(0.5F), succeeded = true)
        val restoration = requireNotNull(state.beginZoomRestoration(0.0F))

        val completion = state.completeZoom(restoration, succeeded = false)

        assertTrue(completion.restorationFailed)
        assertFalse(completion.shouldRestore)
        assertEquals(0.5F, state.beginZoomRestoration(0.0F)?.value)
    }

    @Test
    fun `successful user operation from previous opening is restored on current camera`() {
        val state = CameraControlState()
        state.onCameraOpened()
        val operation = state.beginZoom(0.75F)
        state.onCameraUnavailable()
        state.onCameraOpened()

        val completion = state.completeZoom(operation, succeeded = true)

        assertTrue(completion.shouldRestore)
        assertEquals(0.75F, state.beginZoomRestoration(0.0F)?.value)
    }

    @Test
    fun `parallel torch toggles use latest logical request`() {
        val state = CameraControlState()
        state.onCameraOpened()
        val first = state.beginTorchToggle(currentTorchEnabled = false)
        val second = state.beginTorchToggle(currentTorchEnabled = false)

        state.completeTorch(first, succeeded = true)
        state.completeTorch(second, succeeded = true)

        assertTrue(first.value)
        assertFalse(second.value)
        assertEquals(false, state.beginTorchRestoration(true)?.value)
    }

    @Test
    fun `dispose drops retained controls and ignores stale completions`() {
        val state = CameraControlState()
        state.onCameraOpened()
        val zoom = state.beginZoom(0.75F)
        state.dispose()

        val completion = state.completeZoom(zoom, succeeded = true)

        assertFalse(completion.shouldRestore)
        assertFalse(state.onCameraOpened())
        assertNull(state.beginZoomRestoration(0.0F))
    }
}
