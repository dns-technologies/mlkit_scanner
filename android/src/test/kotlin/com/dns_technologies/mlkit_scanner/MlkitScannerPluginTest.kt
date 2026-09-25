package com.dns_technologies.mlkit_scanner

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import com.dns_technologies.mlkit_scanner.permissions.PermissionGateway
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.junit.Test
import org.mockito.Mockito.*
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner

// region MlkitScannerPluginTest
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
internal class MlkitScannerPluginTest {
    @Test fun `permission completes before scanner receives any capture work`() {
        val f = PermissionFixture()
        val lease = f.plugin.open(1)
        clearInvocations(f.plugin.scanner)
        val reply = f.capture(lease)
        assertEquals(1, f.prompts)
        assertEquals(0, reply.replies)
        verifyNoInteractions(f.plugin.scanner)
        f.complete(false)
        assertEquals(PluginError.AuthorizationCameraError.errorCode, reply.error)
        assertEquals(1, reply.replies)
        verifyNoInteractions(f.plugin.scanner)
    }

    @Test fun `closing capture cannot revive scanner after late permission approval`() {
        val f = PermissionFixture()
        val lease = f.plugin.open(1)
        val reply = f.capture(lease)
        f.plugin.call("closeCapture", mapOf("captureId" to lease))
        clearInvocations(f.plugin.scanner)
        f.complete(true)
        assertEquals(1, reply.replies)
        assertEquals(PluginError.CameraSessionDisposed.errorCode, reply.error)
        verifyNoInteractions(f.plugin.scanner)
    }

    @Test fun `replacement capture shares pending permission prompt without reviving old owner`() {
        val f = PermissionFixture()
        val first = f.capture(f.plugin.open(1))
        val second = f.capture(f.plugin.open(2))
        clearInvocations(f.plugin.scanner)
        assertEquals(1, f.prompts)
        assertEquals(PluginError.CameraSessionDisposed.errorCode, first.error)
        f.complete(false)
        assertEquals(1, first.replies)
        assertEquals(1, second.replies)
        assertEquals(PluginError.AuthorizationCameraError.errorCode, second.error)
        verifyNoInteractions(f.plugin.scanner)
    }

    private class PermissionFixture {
        val plugin = TexturePluginFixture()
        var granted = false
        var prompts = 0
        private val gateway = PermissionGateway(
            permissionChecker = { _, _ -> granted },
            permissionRequester = { _, _, _ -> prompts++ },
        )
        init {
            gateway.attach(mock(Activity::class.java))
            plugin.set("permissions", gateway)
        }
        fun capture(lease: String) = plugin.call("resumeCameraMethod", mapOf(
            "captureId" to lease,
            "configuration" to mapOf("zoomRatio" to 1.0, "torchEnabled" to false),
            "geometry" to mapOf("width" to 100.0, "height" to 100.0),
        ))
        fun complete(allowed: Boolean) {
            granted = allowed
            assertTrue(gateway.onPermissionResult(0, arrayOf(Manifest.permission.CAMERA),
                intArrayOf(if (allowed) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED)))
        }
    }

    @Test fun `engine detach disposes hardware and rejects subsequent work`() {
        val f = TexturePluginFixture(); f.open(1)
        f.plugin.onDetachedFromEngine(mock(FlutterPlugin.FlutterPluginBinding::class.java))
        verify(f.scanner).dispose()
        assertEquals(PluginError.CameraSessionDisposed.errorCode, f.call("registerScanner", mapOf("viewId" to 2)).error)
    }
    @Test fun `invalid registration cannot allocate a capture`() {
        val f = TexturePluginFixture()
        for (id in listOf(-1, true, "1")) {
            assertEquals(PluginError.InvalidArguments.errorCode, f.call("registerScanner", mapOf("viewId" to id)).error)
        }
        assertNotNull(f.call("openCapture", mapOf("viewId" to 1)).error)
    }
}
// endregion

// region MlkitScannerPluginLifecycleTest
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
internal class MlkitScannerPluginLifecycleTest {
    @Test fun `cleanup failure still waits for actual surface disposal before replying`() {
        val f = TexturePluginFixture()
        val disposal = kotlinx.coroutines.CompletableDeferred<Unit>()
        doReturn(disposal).`when`(f.scanner).disposal
        doThrow(IllegalStateException("analyzer cleanup failed")).`when`(f.scanner).dispose()
        val reply = f.call("disposeScanner")
        assertEquals(0, reply.replies)
        disposal.complete(Unit)
        assertEquals(1, reply.replies)
        assertNotNull(reply.error)
    }
    @Test fun `repeated disposal waits for the same scanner resource completion`() {
        val f = TexturePluginFixture()
        val disposal = kotlinx.coroutines.CompletableDeferred<Unit>()
        doReturn(disposal).`when`(f.scanner).disposal
        val first = f.call("disposeScanner")
        val second = f.call("disposeScanner")
        assertEquals(0, first.replies); assertEquals(0, second.replies)
        verify(f.scanner, times(1)).dispose()
        disposal.complete(Unit)
        assertEquals(1, first.replies); assertEquals(1, second.replies)
    }
    @Test fun `logical registration allocates no camera or view`() {
        val f = TexturePluginFixture(); clearInvocations(f.scanner)
        assertNull(f.call("registerScanner", mapOf("viewId" to 1)).error)
        verifyNoInteractions(f.scanner)
    }
    @Test fun `unregistering inactive widget leaves current capture usable`() {
        val f = TexturePluginFixture(); f.open(1); val current = f.open(2)
        f.call("unregisterScanner", mapOf("viewId" to 1))
        assertNull(f.call("setScanDelay", mapOf("captureId" to current, "delay" to 2)).error)
    }
    @Test fun `dispose releases hardware and next lease requires a surviving registration`() {
        val f = TexturePluginFixture(); f.open(1)
        assertNull(f.call("disposeScanner").error); verify(f.scanner).dispose()
        assertTrue(f.call("openCapture", mapOf("viewId" to 1)).value is String)
        f.call("unregisterScanner", mapOf("viewId" to 1))
        assertNotNull(f.call("openCapture", mapOf("viewId" to 1)).error)
    }
    @Test fun `pause succeeds before hardware allocation`() {
        val f = TexturePluginFixture(); f.set("scanner", null)
        val lease = f.open(1)
        val reply = f.call("pauseCameraMethod", mapOf("captureId" to lease))
        assertNull(reply.error); assertEquals(1, reply.replies)
    }
}
// endregion

// region ScannerPluginTransportTest
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
internal class ScannerPluginTransportTest {
    @Test fun `batched settings execute only for the selected lease`() = kotlinx.coroutines.runBlocking<Unit> {
        val f = TexturePluginFixture()
        val old = f.open(1)
        val current = f.open(2)
        clearInvocations(f.scanner)
        val stale = f.call("updateCameraSettings", mapOf("captureId" to old, "zoomRatio" to 2.0, "torchEnabled" to true))
        assertEquals(PluginError.CameraSessionDisposed.errorCode, stale.error)
        assertEquals(1, stale.replies)
        verifyNoInteractions(f.scanner)
        val reply = f.call("updateCameraSettings", mapOf("captureId" to current, "zoomRatio" to 2.0, "torchEnabled" to false))
        verify(f.scanner).setZoomRatio(2.0F)
        verify(f.scanner).setTorch(false)
        assertNull(reply.error)
        assertEquals(1, reply.replies)
    }

    @Test fun `stale lease cannot change or pause current scanner`() {
        val f = TexturePluginFixture()
        val old = f.open(1); val current = f.open(2)
        clearInvocations(f.scanner)
        val reply = f.call("pauseCameraMethod", mapOf("captureId" to old))
        assertEquals(PluginError.CameraSessionDisposed.errorCode, reply.error)
        verifyNoInteractions(f.scanner)
        assertNull(f.call("pauseCameraMethod", mapOf("captureId" to current)).error)
        verify(f.scanner).pauseCamera()
    }
    @Test fun `late close of an old lease leaves current lease valid`() {
        val f = TexturePluginFixture(); val a = f.open(1); val b = f.open(2)
        f.call("closeCapture", mapOf("captureId" to a))
        assertNull(f.call("setScanDelay", mapOf("captureId" to b, "delay" to 100)).error)
        verify(f.scanner).setScanPeriod(100)
    }
    @Test fun `preview subscriptions return a snapshot and independent endpoint identities`() {
        val f = TexturePluginFixture()
        val a = f.call("subscribePreview").value as Map<*, *>
        val b = f.call("subscribePreview").value as Map<*, *>
        assertNotEquals(a["subscriptionId"], b["subscriptionId"])
        assertTrue(a.containsKey("description")); assertNull(a["description"])
        assertNull(f.call("unsubscribePreview", mapOf("subscriptionId" to a["subscriptionId"])).error)
    }
    @Test fun `starting an obsolete scan endpoint is rejected`() {
        val f = TexturePluginFixture(); val lease = f.open(1)
        val old = f.call("subscribeScan", mapOf("captureId" to lease)).value
        val current = f.call("subscribeScan", mapOf("captureId" to lease)).value
        assertNotEquals(old, current)
        assertNotNull(f.call("startScan", mapOf("captureId" to lease, "subscriptionId" to old, "type" to 0, "delay" to 0)).error)
        assertNull(f.call("startScan", mapOf("captureId" to lease, "subscriptionId" to current, "type" to 0, "delay" to 0)).error)
        verify(f.scanner).startScan(0)
    }
}
// endregion
