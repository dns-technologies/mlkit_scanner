package com.dns_technologies.mlkit_scanner

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.util.Size
import com.dns_technologies.mlkit_scanner.permissions.PermissionGateway
import com.dns_technologies.mlkit_scanner.scanner.CaptureLease
import com.dns_technologies.mlkit_scanner.scanner.ScannerConfiguration
import com.dns_technologies.mlkit_scanner.scanner.components.camera.CameraPreviewDescription
import com.dns_technologies.mlkit_scanner.scanner.components.camera.CameraPreviewState
import com.dns_technologies.mlkit_scanner.scanner.components.camera.Rect
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
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
        clearInvocations(f.plugin.scanner)
        val reply = f.capture(1)
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
        val reply = f.capture(1)
        val lease = "permission-1"
        f.plugin.call("release", mapOf("captureId" to lease))
        clearInvocations(f.plugin.scanner)
        f.complete(true)
        assertEquals(1, reply.replies)
        assertEquals(PluginError.CameraSessionDisposed.errorCode, reply.error)
        verifyNoInteractions(f.plugin.scanner)
    }

    @Test fun `replacement capture shares pending permission prompt without reviving old owner`() {
        val f = PermissionFixture()
        val first = f.capture(1)
        val second = f.capture(2)
        clearInvocations(f.plugin.scanner)
        assertEquals(1, f.prompts)
        assertEquals(PluginError.CameraSessionDisposed.errorCode, first.error)
        f.complete(false)
        assertEquals(1, first.replies)
        assertEquals(1, second.replies)
        assertEquals(PluginError.AuthorizationCameraError.errorCode, second.error)
        verifyNoInteractions(f.plugin.scanner)
    }

    @Test fun `Отмена распознавания во время захвата закрывает его незавершённый lease`() {
        val f = PermissionFixture()
        val reply = f.capture(1, "scan")
        assertEquals(0, reply.replies)
        f.plugin.call("cancelScan", mapOf("captureId" to "permission-1"))
        clearInvocations(f.plugin.scanner)

        f.complete(true)

        assertEquals(1, reply.replies)
        assertNull(reply.error)
        verify(f.plugin.scanner, never()).startScan(anyInt())
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
        fun capture(viewId: Int, scan: String? = null) = plugin.call("capture", plugin.arguments(viewId, "permission-$viewId") + mapOf("subscriptionId" to scan))
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
        assertEquals(PluginError.CameraSessionDisposed.errorCode, f.call("capture", f.arguments(2)).error)
    }
    @Test fun `invalid consumer cannot allocate a capture`() {
        val f = TexturePluginFixture()
        for (id in listOf(-1, true, "1")) {
            assertEquals(PluginError.InvalidArguments.errorCode, f.call("capture", f.arguments(id)).error)
        }
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
    @Test fun `dispose allows a fresh capture without retaining registrations`() {
        val f = TexturePluginFixture(); f.open(1)
        assertNull(f.call("disposeScanner").error); verify(f.scanner).dispose()
        f.set("scanner", f.scanner)
        assertNotNull(f.open(1))
    }
    @Test fun `capture waits for previous hardware disposal before selecting the next owner`() = kotlinx.coroutines.runBlocking<Unit> {
        val f = TexturePluginFixture()
        f.open(1)
        val disposal = kotlinx.coroutines.CompletableDeferred<Unit>()
        doReturn(disposal).`when`(f.scanner).disposal
        val disposing = f.call("disposeScanner")
        f.set("scanner", f.scanner)
        clearInvocations(f.scanner)

        val capture = f.call("capture", f.arguments(2))
        val lease = f.plugin.javaClass.getDeclaredField("selected").apply { isAccessible = true }
            .get(f.plugin) as CaptureLease

        assertEquals(0, capture.replies)
        verify(f.scanner, never()).select(lease.consumer)
        verify(f.scanner, never()).capture(ScannerConfiguration())
        disposal.complete(Unit)

        assertEquals(1, disposing.replies)
        assertNull(disposing.error)
        assertEquals(1, capture.replies)
        assertNull(capture.error)
        assertEquals(2, lease.consumer.viewId)
        verify(f.scanner).select(lease.consumer)
        verify(f.scanner).capture(ScannerConfiguration())
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
    @Test fun `geometry conversion preserves rounded dimensions and tiny positive viewports`() {
        val f = TexturePluginFixture()
        val captureId = f.open(1)
        val lease = f.plugin.javaClass.getDeclaredField("selected").apply { isAccessible = true }
            .get(f.plugin) as CaptureLease

        val reply = f.call("updatePreviewGeometry", mapOf(
            "captureId" to captureId, "width" to 100.5, "height" to 0.01,
        ))

        assertNull(reply.error)
        assertEquals(Size(101, 1), lease.consumer.size)
        verify(f.scanner).updateGeometry()
    }

    @Test fun `invalid geometry leaves the viewport and scanner untouched`() {
        val f = TexturePluginFixture()
        val captureId = f.open(1)
        val lease = f.plugin.javaClass.getDeclaredField("selected").apply { isAccessible = true }
            .get(f.plugin) as CaptureLease
        clearInvocations(f.scanner)

        for (width in listOf(null, true, "100", 0, -1, Double.NaN, Double.POSITIVE_INFINITY,
            Int.MAX_VALUE.toDouble() + 1)) {
            val reply = f.call("updatePreviewGeometry", mapOf(
                "captureId" to captureId, "width" to width, "height" to 100,
            ))
            assertEquals("Invalid width: $width", PluginError.InvalidArguments.errorCode, reply.error)
            assertEquals(1, reply.replies)
        }

        assertEquals(Size(1, 1), lease.consumer.size)
        verifyNoInteractions(f.scanner)
    }

    @Test fun `preview registration and subscriptions preserve the complete channel snapshot`() {
        val f = TexturePluginFixture()
        val description = mapOf<String, Any>(
            "textureId" to 7L, "width" to 1280, "height" to 720,
            "rotationDegrees" to 90, "mirrored" to false, "state" to "streaming",
            "cropLeft" to 160, "cropTop" to 0, "cropWidth" to 960, "cropHeight" to 720,
        )
        val preview = CameraPreviewDescription(
            textureId = 7, width = 1280, height = 720, rotationDegrees = 90,
            mirrored = false, state = CameraPreviewState.Streaming,
            cropRect = Rect(160, 0, 1120, 720),
        )
        f.plugin.javaClass.getDeclaredMethod("publishPreview", CameraPreviewDescription::class.java)
            .apply { isAccessible = true }.invoke(f.plugin, preview)

        val subscription = f.call("subscribePreview").value as Map<*, *>

        assertEquals(description, subscription["description"])
    }

    @Test fun `preview events preserve state names and explicit output withdrawal`() {
        val f = TexturePluginFixture()
        val channel = mock(MethodChannel::class.java)
        f.set("channel", channel)
        val subscription = f.call("subscribePreview").value as Map<*, *>
        val publish = f.plugin.javaClass
            .getDeclaredMethod("publishPreview", CameraPreviewDescription::class.java)
            .apply { isAccessible = true }
        val preview = CameraPreviewDescription(
            textureId = 7, width = 720, height = 1280, rotationDegrees = 0,
            mirrored = true, state = CameraPreviewState.Starting,
            cropRect = Rect(0, 0, 720, 1280),
        )

        for ((state, name) in listOf(
            CameraPreviewState.Starting to "starting",
            CameraPreviewState.Streaming to "streaming",
            CameraPreviewState.Paused to "paused",
        )) {
            publish.invoke(f.plugin, preview.copy(state = state))

            verify(channel).invokeMethod("onPreviewState", mapOf(
                "subscriptionId" to subscription["subscriptionId"],
                "description" to mapOf(
                    "textureId" to 7L, "width" to 720, "height" to 1280,
                    "rotationDegrees" to 0, "mirrored" to true, "state" to name,
                    "cropLeft" to 0, "cropTop" to 0, "cropWidth" to 720, "cropHeight" to 1280,
                ),
            ))
        }
        publish.invoke(f.plugin, null)

        verify(channel).invokeMethod("onPreviewState", mapOf(
            "subscriptionId" to subscription["subscriptionId"], "description" to null,
        ))
        assertNull((f.call("subscribePreview").value as Map<*, *>)["description"])
    }

    @Test fun `batched settings reject an obsolete lease before validating the settings`() = kotlinx.coroutines.runBlocking<Unit> {
        val f = TexturePluginFixture()
        val old = f.open(1)
        val current = f.open(2)
        clearInvocations(f.scanner)
        val stale = f.call("updateCameraSettings", mapOf("captureId" to old, "torchEnabled" to 1))
        assertEquals(PluginError.CameraSessionDisposed.errorCode, stale.error)
        assertEquals(1, stale.replies)
        val malformed = f.call("updateCameraSettings", mapOf("captureId" to current, "torchEnabled" to 1))
        assertEquals(PluginError.InvalidArguments.errorCode, malformed.error)
        assertEquals(1, malformed.replies)
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
        f.call("release", mapOf("captureId" to a))
        assertNull(f.call("setScanDelay", mapOf("captureId" to b, "delay" to 100)).error)
        verify(f.scanner).setScanPeriod(100)
    }
    @Test fun `preview subscriptions return a snapshot and independent subscription identities`() {
        val f = TexturePluginFixture()
        val a = f.call("subscribePreview").value as Map<*, *>
        val b = f.call("subscribePreview").value as Map<*, *>
        assertNotEquals(a["subscriptionId"], b["subscriptionId"])
        assertTrue(a.containsKey("description")); assertNull(a["description"])
        assertNull(f.call("unsubscribePreview", mapOf("subscriptionId" to a["subscriptionId"])).error)
    }
    @Test fun `unsubscribing preview stops delivery without removing other subscribers`() {
        val f = TexturePluginFixture()
        val channel = mock(MethodChannel::class.java)
        f.set("channel", channel)
        val removed = f.call("subscribePreview").value as Map<*, *>
        val active = f.call("subscribePreview").value as Map<*, *>
        val capture = f.open(1)
        f.call("release", mapOf("captureId" to capture))

        f.call("unsubscribePreview", mapOf("subscriptionId" to removed["subscriptionId"]))
        f.call("disposeScanner")

        verify(channel).invokeMethod("onPreviewState", mapOf(
            "subscriptionId" to active["subscriptionId"], "description" to null,
        ))
        verifyNoMoreInteractions(channel)
    }

    @Test fun `preview unsubscribe during delivery suppresses remaining queued subscribers`() {
        val f = TexturePluginFixture()
        val channel = mock(MethodChannel::class.java)
        f.set("channel", channel)
        val subscriptions = List(2) { f.call("subscribePreview").value as Map<*, *> }
        doAnswer {
            subscriptions.forEach {
                f.call("unsubscribePreview", mapOf("subscriptionId" to it["subscriptionId"]))
            }
            null
        }.`when`(channel).invokeMethod(eq("onPreviewState"), any())

        f.call("disposeScanner")

        verify(channel, times(1)).invokeMethod(eq("onPreviewState"), any())
    }

    @Test fun `Отмена закрывает владение результатами и сохраняет захват`() {
        val f = TexturePluginFixture()
        val capture = f.open(1)
        f.call("startScan", mapOf("captureId" to capture, "subscriptionId" to "first", "delay" to 0))
        val lease = f.plugin.javaClass.getDeclaredField("selected").apply { isAccessible = true }.get(f.plugin) as CaptureLease
        val oldScan = lease.scanLease!!

        assertNull(f.call("cancelScan", mapOf("captureId" to capture)).error)
        assertTrue(oldScan.closed)
        assertFalse(oldScan.enabled)
        assertFalse(lease.closed)
        assertNull(lease.scanLease)

        assertNull(f.call("startScan", mapOf("captureId" to capture, "subscriptionId" to "second", "delay" to 0)).error)
        assertNotSame(oldScan, lease.scanLease)
        assertTrue(oldScan.closed)
        assertTrue(lease.scanLease!!.enabled)
    }

    @Test fun `Некорректная задержка не изменяет текущий поток`() {
        val f = TexturePluginFixture()
        val capture = f.open(1)
        clearInvocations(f.scanner)

        for (delay in listOf(-1, 0.5, true, "1")) {
            val reply = f.call("startScan", mapOf("captureId" to capture, "subscriptionId" to "scan", "delay" to delay))
            assertEquals(PluginError.InvalidArguments.errorCode, reply.error)
        }

        verifyNoInteractions(f.scanner)
        assertNull(f.call("startScan", mapOf("captureId" to capture, "subscriptionId" to "scan", "delay" to 250)).error)
        verify(f.scanner).startScan(250)
    }

    @Test fun `missing or empty scan endpoint leaves the current recognition subscription active`() {
        val f = TexturePluginFixture()
        val capture = f.open(1)
        f.call("startScan", mapOf("captureId" to capture, "subscriptionId" to "active", "delay" to 0))
        val lease = f.plugin.javaClass.getDeclaredField("selected").apply { isAccessible = true }
            .get(f.plugin) as CaptureLease
        val active = lease.scanLease!!
        clearInvocations(f.scanner)

        for (endpoint in listOf(null, "", 1)) {
            val reply = f.call("startScan", mapOf("captureId" to capture, "subscriptionId" to endpoint, "delay" to 0))

            assertEquals(PluginError.InvalidArguments.errorCode, reply.error)
            assertEquals(1, reply.replies)
            assertSame(active, lease.scanLease)
            assertTrue(active.enabled)
            assertFalse(active.closed)
        }

        verifyNoInteractions(f.scanner)
    }

    @Test fun `Новый поток закрывает предыдущий lease без остановки камеры`() {
        val f = TexturePluginFixture()
        val capture = f.open(1)
        f.call("startScan", mapOf("captureId" to capture, "subscriptionId" to "first", "delay" to 0))
        val lease = f.plugin.javaClass.getDeclaredField("selected").apply { isAccessible = true }.get(f.plugin) as CaptureLease
        val oldScan = lease.scanLease!!
        clearInvocations(f.scanner)

        val result = f.call("startScan", mapOf("captureId" to capture, "subscriptionId" to "second", "delay" to 0))

        assertNull(result.error)
        assertTrue(oldScan.closed)
        assertEquals("second", lease.scanLease!!.id)
        verify(f.scanner).pauseScan()
        verify(f.scanner).startScan(0)
        verify(f.scanner, never()).pauseCamera()
    }

}
// endregion
