package com.dns_technologies.mlkit_scanner.scanner.utils

import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Thread-safe pool that never shares a byte array between active leases. */
internal class ReusableByteArrayPool(
    private val maxRetainedBuffers: Int = 1,
) {
    private val lock = ReentrantLock()
    private val availableBuffers = ArrayDeque<ByteArray>()
    private var isDisposed = false

    init {
        require(maxRetainedBuffers > 0)
    }

    /** Acquires exclusive ownership of an array with the requested exact size. */
    fun acquire(size: Int): ByteArrayLease {
        require(size > 0)
        val buffer = lock.withLock {
            if (isDisposed) ByteArray(size) else takeBuffer(size) ?: ByteArray(size)
        }
        return ByteArrayLease(buffer) { release(it) }
    }

    /** Drops retained buffers; later acquisitions stay valid but are no longer pooled. */
    fun dispose() {
        lock.withLock {
            isDisposed = true
            availableBuffers.clear()
        }
    }

    private fun takeBuffer(size: Int): ByteArray? {
        val iterator = availableBuffers.iterator()
        while (iterator.hasNext()) {
            val candidate = iterator.next()
            if (candidate.size == size) {
                iterator.remove()
                return candidate
            }
        }
        return null
    }

    private fun release(buffer: ByteArray) {
        lock.withLock {
            if (isDisposed) return
            if (availableBuffers.size == maxRetainedBuffers) {
                availableBuffers.removeFirst()
            }
            availableBuffers.addLast(buffer)
        }
    }
}

/** Exclusive, idempotently releasable ownership of a pooled byte array. */
internal class ByteArrayLease(
    val data: ByteArray,
    private val release: (ByteArray) -> Unit,
) : AutoCloseable {
    private val isClosed = AtomicBoolean(false)

    override fun close() {
        if (isClosed.compareAndSet(false, true)) {
            release(data)
        }
    }
}
