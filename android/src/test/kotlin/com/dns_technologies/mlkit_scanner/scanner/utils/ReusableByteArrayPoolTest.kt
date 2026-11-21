package com.dns_technologies.mlkit_scanner.scanner.utils

import org.junit.Assert.assertNotSame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal class ReusableByteArrayPoolTest {
    @Test
    fun `released buffer is reused for matching size`() {
        val pool = ReusableByteArrayPool()
        val first = pool.acquire(16)
        val firstData = first.data
        first.close()

        val second = pool.acquire(16)

        assertSame(firstData, second.data)
        second.close()
    }

    @Test
    fun `active leases never share a buffer`() {
        val pool = ReusableByteArrayPool()
        val first = pool.acquire(16)
        val second = pool.acquire(16)

        assertNotSame(first.data, second.data)

        first.close()
        second.close()
    }

    @Test
    fun `closing lease twice does not return same buffer twice`() {
        val pool = ReusableByteArrayPool(maxRetainedBuffers = 2)
        val released = pool.acquire(16)
        released.close()
        released.close()

        val first = pool.acquire(16)
        val second = pool.acquire(16)

        assertNotSame(first.data, second.data)
        first.close()
        second.close()
    }

    @Test
    fun `concurrent leases have exclusive buffers`() {
        val pool = ReusableByteArrayPool()
        val executor = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        val acquired = CountDownLatch(4)
        val release = CountDownLatch(1)
        val buffers = Collections.synchronizedSet(
            Collections.newSetFromMap(IdentityHashMap<ByteArray, Boolean>()),
        )

        try {
            repeat(4) {
                executor.submit {
                    start.await()
                    val lease = pool.acquire(32)
                    buffers += lease.data
                    acquired.countDown()
                    release.await()
                    lease.close()
                }
            }
            start.countDown()
            assertTrue(acquired.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS))

            assertEquals(4, buffers.size)
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `disposed pool does not retain returned buffers`() {
        val pool = ReusableByteArrayPool()
        val first = pool.acquire(16)
        val firstData = first.data
        pool.dispose()
        first.close()

        val second = pool.acquire(16)
        val secondData = second.data
        second.close()
        val third = pool.acquire(16)

        assertNotSame(firstData, secondData)
        assertNotSame(secondData, third.data)
        third.close()
    }

    private companion object {
        const val TEST_TIMEOUT_MS = 1_000L
    }
}
