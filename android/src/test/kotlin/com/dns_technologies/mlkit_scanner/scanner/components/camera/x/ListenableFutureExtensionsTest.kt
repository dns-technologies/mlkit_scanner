package com.dns_technologies.mlkit_scanner.scanner.components.camera.x

import com.dns_technologies.mlkit_scanner.CameraControlOperation
import com.dns_technologies.mlkit_scanner.PluginError
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

internal class ListenableFutureExtensionsTest {
    @Test
    fun `camera control deferred completes with future`() = runBlocking {
        val future = TestListenableFuture()
        val result = future.asCameraControlDeferred(
            Runnable::run,
            CameraControlOperation.ZOOM,
        )

        assertFalse(result.isCompleted)
        future.complete()

        result.await()
        assertTrue(result.isCompleted)
    }

    @Test
    fun `camera control failure preserves operation and original cause`() = runBlocking {
        val future = TestListenableFuture()
        val result = future.asCameraControlDeferred(
            Runnable::run,
            CameraControlOperation.TORCH,
        )
        val cause = IllegalStateException("camera rejected operation")

        future.completeExceptionally(cause)

        val error = runCatching { result.await() }.exceptionOrNull()
        assertTrue(error is PluginError.CameraControlError)
        error as PluginError.CameraControlError
        assertEquals(CameraControlOperation.TORCH, error.operation)
        assertSame(cause, error.cause)
    }

    @Test
    fun `cancelling deferred cancels camera control future`() {
        val future = TestListenableFuture()
        val result = future.asCameraControlDeferred(
            Runnable::run,
            CameraControlOperation.FOCUS,
        )

        result.cancel()

        assertTrue(future.isCancelled)
    }

    private class TestListenableFuture : ListenableFuture<Void> {
        private val delegate = CompletableFuture<Void>()
        private val listeners = mutableListOf<Pair<Runnable, Executor>>()

        override fun addListener(listener: Runnable, executor: Executor) {
            val executeImmediately = synchronized(listeners) {
                if (delegate.isDone) {
                    true
                } else {
                    listeners += listener to executor
                    false
                }
            }
            if (executeImmediately) executor.execute(listener)
        }

        fun complete() {
            delegate.complete(null)
            notifyListeners()
        }

        fun completeExceptionally(error: Throwable) {
            delegate.completeExceptionally(error)
            notifyListeners()
        }

        override fun cancel(mayInterruptIfRunning: Boolean): Boolean =
            delegate.cancel(mayInterruptIfRunning).also { if (it) notifyListeners() }

        override fun isCancelled(): Boolean = delegate.isCancelled

        override fun isDone(): Boolean = delegate.isDone

        override fun get(): Void? = delegate.get()

        override fun get(timeout: Long, unit: TimeUnit): Void? = delegate.get(timeout, unit)

        private fun notifyListeners() {
            val awaiting = synchronized(listeners) {
                listeners.toList().also { listeners.clear() }
            }
            awaiting.forEach { (listener, executor) -> executor.execute(listener) }
        }
    }
}
