package com.dns_technologies.mlkit_scanner.scanner.components.camera.x

import com.dns_technologies.mlkit_scanner.CameraControlOperation
import com.dns_technologies.mlkit_scanner.PluginError
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

internal class CameraRuntimeStateTest {
    @Test
    fun `successful controls are retained and restored after camera reopens`() {
        val state = CameraRuntimeState()
        assertTrue(state.onCameraOpened())
        val zoom = state.beginZoom(0.75F)
        val torch = state.beginTorch(true)

        state.completeZoom(zoom, succeeded = true)
        state.completeTorch(torch, succeeded = true)
        state.onCameraUnavailable()
        assertTrue(state.onCameraOpened())

        assertEquals(0.75F, state.beginZoomRestoration(0.0F)?.value)
        assertEquals(true, state.beginTorchRestoration(false)?.value)
    }

    @Test
    fun `matching controls and duplicate open event do not start restoration`() {
        val state = CameraRuntimeState()
        state.onCameraOpened()
        state.completeZoom(state.beginZoom(0.5F), succeeded = true)
        state.completeTorch(
            state.beginTorch(true),
            succeeded = true,
        )

        assertFalse(state.onCameraOpened())
        assertNull(state.beginZoomRestoration(0.5F))
        assertNull(state.beginTorchRestoration(true))
    }

    @Test
    fun `new camera opening can force controls despite stale CameraInfo values`() {
        val state = CameraRuntimeState()
        state.onCameraOpened()
        state.completeZoom(state.beginZoom(0.5F), succeeded = true)
        state.completeTorch(
            state.beginTorch(true),
            succeeded = true,
        )
        state.onCameraUnavailable()
        state.onCameraOpened()

        assertEquals(0.5F, state.beginZoomRestoration(0.5F, force = true)?.value)
        assertEquals(true, state.beginTorchRestoration(true, force = true)?.value)
    }

    @Test
    fun `failed user controls preserve last confirmed values`() {
        val state = CameraRuntimeState()
        state.onCameraOpened()
        state.completeZoom(state.beginZoom(0.25F), succeeded = true)
        state.completeTorch(
            state.beginTorch(true),
            succeeded = true,
        )

        val zoomCompletion = state.completeZoom(state.beginZoom(0.75F), succeeded = false)
        val torchCompletion = state.completeTorch(
            state.beginTorch(false),
            succeeded = false,
        )

        assertTrue(zoomCompletion.shouldRestore)
        assertTrue(torchCompletion.shouldRestore)
        assertEquals(0.25F, state.beginZoomRestoration(0.0F)?.value)
        assertEquals(true, state.beginTorchRestoration(false)?.value)
    }

    @Test
    fun `stale successful operation cannot overwrite a newer request`() {
        val state = CameraRuntimeState()
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
        val state = CameraRuntimeState()
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
        val state = CameraRuntimeState()
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
        val state = CameraRuntimeState()
        state.onCameraOpened()
        val operation = state.beginZoom(0.75F)
        state.onCameraUnavailable()
        state.onCameraOpened()

        val completion = state.completeZoom(operation, succeeded = true)

        assertTrue(completion.shouldRestore)
        assertEquals(0.75F, state.beginZoomRestoration(0.0F)?.value)
    }

    @Test
    fun `parallel torch updates retain latest absolute request`() {
        val state = CameraRuntimeState()
        state.onCameraOpened()
        val first = state.beginTorch(true)
        val second = state.beginTorch(false)

        state.completeTorch(first, succeeded = true)
        state.completeTorch(second, succeeded = true)

        assertTrue(first.value)
        assertFalse(second.value)
        assertEquals(false, state.beginTorchRestoration(true)?.value)
    }

    @Test
    fun `dispose drops retained controls and ignores stale completions`() {
        val state = CameraRuntimeState()
        state.onCameraOpened()
        val zoom = state.beginZoom(0.75F)
        state.dispose()

        val completion = state.completeZoom(zoom, succeeded = true)

        assertFalse(completion.shouldRestore)
        assertFalse(state.onCameraOpened())
        assertNull(state.beginZoomRestoration(0.0F))
    }

    @Test
    fun `waiter completes only after camera opens`() = runBlocking {
        val state = CameraRuntimeState()
        val waiter = state.awaitOpen()

        assertFalse(waiter.isCompleted)

        state.onCameraOpened()

        waiter.await()
        assertTrue(state.awaitOpen().isCompleted)
    }

    @Test
    fun `camera unavailable requires the next opening`() = runBlocking {
        val state = CameraRuntimeState()
        state.onCameraOpened()
        state.onCameraUnavailable()

        val waiter = state.awaitOpen()

        assertFalse(waiter.isCompleted)
        state.onCameraOpened()
        waiter.await()
    }

    @Test
    fun `camera error fails current waiters but allows a later opening`() = runBlocking {
        val state = CameraRuntimeState()
        val failedWaiter = state.awaitOpen()
        val cameraError = PluginError.CameraControlError(
            CameraControlOperation.AWAIT_OPEN,
            cameraStateErrorCode = 4,
        )

        state.onCameraUnavailable(cameraError)

        assertSame(
            cameraError,
            runCatching { failedWaiter.await() }.exceptionOrNull(),
        )
        val recoveredWaiter = state.awaitOpen()
        assertFalse(recoveredWaiter.isCompleted)
        state.onCameraOpened()
        recoveredWaiter.await()
    }

    @Test
    fun `dispose fails current and future waiters`() = runBlocking {
        val state = CameraRuntimeState()
        val currentWaiter = state.awaitOpen()

        state.dispose()

        assertSame(
            PluginError.CameraSessionDisposed,
            runCatching { currentWaiter.await() }.exceptionOrNull(),
        )
        assertSame(
            PluginError.CameraSessionDisposed,
            runCatching { state.awaitOpen().await() }.exceptionOrNull(),
        )
    }
}
