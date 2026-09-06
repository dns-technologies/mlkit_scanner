package com.dns_technologies.mlkit_scanner.scanner.components.camera.x

import com.dns_technologies.mlkit_scanner.CameraControlOperation
import com.dns_technologies.mlkit_scanner.PluginError
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.CancellationException
import java.util.concurrent.Executor
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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

    private fun withResetFocus(future: ListenableFuture<*>, block: suspend (Deferred<Unit>) -> Unit) =
        withCameraFixture { f ->
            f.start()
            doReturn(future).`when`(f.control).cancelFocusAndMetering()
            runBlocking { block(f.camera.resetFocus()) }
        }
}
