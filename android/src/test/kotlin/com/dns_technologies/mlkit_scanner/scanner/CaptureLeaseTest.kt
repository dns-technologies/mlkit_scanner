package com.dns_technologies.mlkit_scanner.scanner

import com.dns_technologies.mlkit_scanner.TexturePluginFixture
import com.dns_technologies.mlkit_scanner.scanner.ScannerConsumer
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
internal class CaptureLeaseTest {
    @Test fun `closing capture cancels pending operation and completes reply exactly once`() {
        val lease = CaptureLease(ScannerConsumer(1), "capture")
        val result = TexturePluginFixture.Reply()
        val reply = lease.reply(result)
        val permission = CompletableDeferred<Boolean>()
        val work = lease.scope.launch(start = CoroutineStart.UNDISPATCHED) { permission.await() }
        lease.close()
        assertTrue(work.isCancelled)
        assertEquals(1, result.replies)
        assertNotNull(result.error)
        reply.success(null); lease.close()
        assertEquals(1, result.replies)
    }
    @Test fun `closing capture revokes its scan subscription`() {
        val lease = CaptureLease(ScannerConsumer(1), "capture")
        val subscription = ScanLease("1").apply { enabled = true }
        lease.scanLease = subscription

        lease.close()
        lease.close()

        assertTrue(subscription.closed)
        assertFalse(subscription.enabled)
        assertNull(lease.scanLease)
    }

    @Test fun `same consumer receives distinct ownership leases`() {
        val consumer = ScannerConsumer(1)
        val a = CaptureLease(consumer, "a"); val b = CaptureLease(consumer, "b")
        assertNotEquals(a.id, b.id)
        a.close(); assertFalse(b.closed); b.close()
    }
}
