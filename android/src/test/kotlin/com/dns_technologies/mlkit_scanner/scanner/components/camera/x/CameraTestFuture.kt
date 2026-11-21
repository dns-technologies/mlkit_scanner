package com.dns_technologies.mlkit_scanner.scanner.components.camera.x

import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/** Controllable completion without starting CameraX or sleeping in asynchronous tests. */
internal class CameraTestFuture<T> : ListenableFuture<T> {
    private val delegate = CompletableFuture<T>()
    var interruptionRequested: Boolean? = null
        private set

    fun complete(value: T) = delegate.complete(value)
    fun fail(error: Throwable) = delegate.completeExceptionally(error)

    override fun addListener(listener: Runnable, executor: Executor) {
        delegate.whenComplete { _, _ -> executor.execute(listener) }
    }

    override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
        interruptionRequested = mayInterruptIfRunning
        return delegate.cancel(mayInterruptIfRunning)
    }

    override fun isDone() = delegate.isDone
    override fun isCancelled() = delegate.isCancelled
    override fun get(): T = delegate.get()
    override fun get(timeout: Long, unit: TimeUnit): T = delegate.get(timeout, unit)
}
