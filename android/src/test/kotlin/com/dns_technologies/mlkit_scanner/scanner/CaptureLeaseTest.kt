package com.dns_technologies.mlkit_scanner.scanner

import com.dns_technologies.mlkit_scanner.TexturePluginFixture
import android.os.Looper
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
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
        val subscription = ScanLease("1", 0).apply { enabled = true }
        lease.scanLease = subscription

        lease.close()
        lease.close()

        assertTrue(subscription.closed)
        assertFalse(subscription.enabled)
        assertNull(lease.scanLease)
    }

    @Test fun `reentrant capture cancellation replies even if the superseded work has not started`() {
        val lease = CaptureLease(ScannerConsumer(1), "capture")
        val thirdResult = TexturePluginFixture.Reply()
        val third = lease.reply(thirdResult)
        val first = lease.reply(object : MethodChannel.Result {
            override fun success(result: Any?) = error("unexpected success")
            override fun notImplemented() = error("unexpected notImplemented")

            override fun error(code: String, message: String?, details: Any?) {
                lease.launchCapture(third) { third.success(null) }
            }
        })
        lease.launchCapture(first) { awaitCancellation() }
        shadowOf(Looper.getMainLooper()).idle()
        val secondResult = TexturePluginFixture.Reply()
        val second = lease.reply(secondResult)

        lease.launchCapture(second) { second.success(null) }
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1, secondResult.replies)
        assertNotNull(secondResult.error)
        assertEquals(1, thirdResult.replies)
        assertNull(thirdResult.error)
        lease.close()
    }

    @Test fun `same consumer receives distinct ownership leases`() {
        val consumer = ScannerConsumer(1)
        val a = CaptureLease(consumer, "a"); val b = CaptureLease(consumer, "b")
        assertNotEquals(a.id, b.id)
        a.close(); assertFalse(b.closed); b.close()
    }
}
