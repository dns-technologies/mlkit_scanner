package com.dns_technologies.mlkit_scanner.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal class ScanResultSubscriptionTest {
    @Test
    fun `cancel releases subscription once`() {
        var cancellationCalls = 0
        val subscription = ScanResultSubscription { cancellationCalls += 1 }

        subscription.cancel()
        subscription.cancel()

        assertEquals(1, cancellationCalls)
    }

    @Test
    fun `concurrent cancellation invokes cleanup only once`() {
        val calls = AtomicInteger()
        val ready = CountDownLatch(4)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(4)
        val subscription = ScanResultSubscription { calls.incrementAndGet() }
        try {
            val cancellations = (1..4).map {
                executor.submit {
                    ready.countDown()
                    assertTrue(start.await(1, TimeUnit.SECONDS))
                    subscription.cancel()
                }
            }
            assertTrue(ready.await(1, TimeUnit.SECONDS))
            start.countDown()
            cancellations.forEach { it.get(1, TimeUnit.SECONDS) }
            assertEquals(1, calls.get())
        } finally {
            start.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `reentrant cancellation is harmless`() {
        var calls = 0
        lateinit var subscription: ScanResultSubscription
        subscription = ScanResultSubscription {
            calls++
            subscription.cancel()
        }

        subscription.cancel()

        assertEquals(1, calls)
    }

    @Test
    fun `throwing cancellation is not retried`() {
        val failure = IllegalStateException("Cleanup failed")
        var calls = 0
        val subscription = ScanResultSubscription {
            calls++
            throw failure
        }

        assertSame(failure, runCatching { subscription.cancel() }.exceptionOrNull())
        subscription.cancel()
        assertEquals(1, calls)
    }
}
