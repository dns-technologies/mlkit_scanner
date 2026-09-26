package com.dns_technologies.mlkit_scanner.scanner.utils

import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Thread-safe pool that never shares a byte array between active leases.
 *
 * @property maxRetainedBuffers Maximum idle arrays retained between conversion requests.
 */
internal class ReusableByteArrayPool(private val maxRetainedBuffers: Int = 1) {
    /** Protects retained buffers and the terminal pooling state. */
    private val lock = ReentrantLock()
    /** Unleased arrays ordered from oldest to newest return. */
    private val availableBuffers = ArrayDeque<ByteArray>()
    /** Disables retention while allowing independent temporary acquisitions. */
    private var isDisposed = false

    init {
        require(maxRetainedBuffers > 0)
    }

    /** Acquires exclusive ownership of an array with the requested exact size. */
    fun acquire(size: Int): ByteArrayLease {
        require(size > 0)
        val buffer =
            lock.withLock {
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

    /** Removes and returns one retained buffer whose size exactly matches [size]. */
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

    /** Returns a lease's buffer to the bounded pool unless pooling was disposed. */
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

/**
 * Exclusive, idempotently releasable ownership of a pooled byte array.
 *
 * @property data Exclusively borrowed bytes valid until this lease closes.
 * @property release Returns this lease's array to its owning pool.
 */
internal class ByteArrayLease(val data: ByteArray, private val release: (ByteArray) -> Unit) :
    AutoCloseable {
    /** Atomic guard ensuring the buffer is returned at most once. */
    private val isClosed = AtomicBoolean(false)

    /** Returns [data] to its owner once; subsequent calls have no effect. */
    override fun close() {
        if (isClosed.compareAndSet(false, true)) {
            release(data)
        }
    }
}
