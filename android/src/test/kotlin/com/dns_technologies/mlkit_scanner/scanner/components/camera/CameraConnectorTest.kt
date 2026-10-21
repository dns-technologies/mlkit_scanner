package com.dns_technologies.mlkit_scanner.scanner.components.camera

import androidx.lifecycle.LifecycleOwner
import com.dns_technologies.mlkit_scanner.PluginError
import java.util.concurrent.ExecutorService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify

internal class CameraConnectorTest {
    @Test
    fun `camera failure keeps its description and original cause when adding the current view`() = runBlocking<Unit> {
        val f = Fixture()
        try {
            val connected = f.connect()
            f.bindings.single().initialized()
            f.bindings.single().availability(CameraAvailability.Open)
            connected.await()
            val cause = IllegalStateException("Camera disconnected")
            val failure = PluginError.CameraError("Failed to set camera zoom ratio to 2.0", cause = cause)
            val pending = CompletableDeferred<Unit>().apply { completeExceptionally(failure) }
            doReturn(pending).`when`(f.camera).setZoomRatio(2F)

            val error = runCatching { f.connector.setZoomRatio(42, 2F) }.exceptionOrNull() as PluginError.CameraError

            assertEquals(failure.message, error.message)
            assertEquals(42, error.viewId)
            assertSame(cause, error.cause)
        } finally { f.close() }
    }

    @Test
    fun `controls before readiness report camera state without requiring an operation tag`() = runBlocking<Unit> {
        val f = Fixture()
        try {
            val error = runCatching { f.connector.setZoomRatio(42, 2F) }.exceptionOrNull() as PluginError.CameraError

            assertTrue(error.message.contains("not ready"))
            assertEquals(42, error.viewId)
        } finally { f.close() }
    }

    @Test
    fun `concurrent readiness waits share one binding and executor`() = runBlocking<Unit> {
        val f = Fixture()
        try {
            val first = f.connect()
            val second = f.connect()
            assertEquals(1, f.bindings.size)
            assertEquals(1, f.executorRequests)
            f.bindings.single().initialized()
            assertFalse(first.isCompleted)
            assertFalse(second.isCompleted)

            f.bindings.single().availability(CameraAvailability.Open)
            first.await()
            second.await()
            f.connect().await()

            assertTrue(f.connector.isReady)
            assertEquals(1, f.bindings.size)
            assertEquals(1, f.executorRequests)
        } finally { f.close() }
    }

    @Test
    fun `unbind rejects old callbacks when reconnecting the same lifecycle owner`() = runBlocking<Unit> {
        val f = Fixture()
        try {
            val previous = f.connect()
            val old = f.bindings.single()
            old.initialized()
            old.availability(CameraAvailability.Open)
            previous.await()
            f.connector.unbind()
            val current = f.connect()

            old.availability(CameraAvailability.Open)
            old.failed(IllegalStateException("old binding"))
            f.bindings.last().initialized()

            assertFalse(current.isCompleted)
            assertFalse(f.connector.isReady)
            assertTrue(f.errors.isEmpty())
            assertEquals(1, f.availabilityChanges)
            verify(f.camera).unbind()

            f.bindings.last().availability(CameraAvailability.Open)
            current.await()
            assertTrue(f.connector.isReady)
            assertEquals(2, f.availabilityChanges)
        } finally { f.close() }
    }

    @Test
    fun `failing disposal still terminates readiness and ignores late callbacks`() = runBlocking<Unit> {
        val f = Fixture()
        try {
            val pending = f.connect()
            val callbacks = f.bindings.single()
            val cleanup = IllegalStateException("dispose")
            doThrow(cleanup).`when`(f.camera).dispose()

            assertSame(cleanup, runCatching { f.connector.dispose(PluginError.CameraSessionDisposed) }.exceptionOrNull())
            assertSame(PluginError.CameraSessionDisposed, runCatching { pending.await() }.exceptionOrNull())
            callbacks.initialized()
            callbacks.availability(CameraAvailability.Open)
            callbacks.failed(IllegalStateException("late failure"))
            f.connector.dispose(PluginError.CameraSessionDisposed)
            val retry = f.connect()

            assertTrue(runCatching { retry.await() }.exceptionOrNull() is IllegalStateException)
            assertFalse(f.connector.isReady)
            assertTrue(f.errors.isEmpty())
            assertEquals(0, f.availabilityChanges)
            assertEquals(1, f.bindings.size)
            verify(f.camera).dispose()
        } finally { f.close() }
    }

    private class Fixture {
        val camera = mock(Camera::class.java)
        val connector = CameraConnector(camera)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val owner = mock(LifecycleOwner::class.java)
        val executor = mock(ExecutorService::class.java)
        val bindings = mutableListOf<Callbacks>()
        val errors = mutableListOf<Exception>()
        var executorRequests = 0
        var availabilityChanges = 0

        init {
            doAnswer {
                bindings += Callbacks(it.getArgument(3), it.getArgument(4), it.getArgument(5))
                null
            }.`when`(camera).bind(anyValue(), anyValue(), anyValue(), anyValue(), anyValue(), anyValue())
        }

        fun connect(): Deferred<Unit> = scope.async {
            connector.awaitReady(
                owner,
                { executorRequests++; executor },
                {},
                scope,
                { 42 },
                { availabilityChanges++ },
                { errors += it },
            )
        }

        fun close() {
            try {
                connector.dispose(PluginError.CameraSessionDisposed)
            } finally {
                scope.cancel()
            }
        }
    }

    private data class Callbacks(
        val availability: OnCameraAvailabilityChanged,
        val initialized: OnInit,
        val failed: OnError,
    )

    private companion object {
        fun <T> anyValue(): T = any<T>()
    }
}
