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
        val lease = CaptureLease(ScannerConsumer(1))
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
    @Test fun `same consumer receives distinct ownership leases`() {
        val consumer = ScannerConsumer(1)
        val a = CaptureLease(consumer); val b = CaptureLease(consumer)
        assertNotEquals(a.id, b.id)
        a.close(); assertFalse(b.closed); b.close()
    }
}
