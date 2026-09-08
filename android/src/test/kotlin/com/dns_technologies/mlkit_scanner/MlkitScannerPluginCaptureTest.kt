package com.dns_technologies.mlkit_scanner

import com.dns_technologies.mlkit_scanner.CameraControlOperation
import com.dns_technologies.mlkit_scanner.PluginError
import com.dns_technologies.mlkit_scanner.permissions.PermissionGateway
import com.dns_technologies.mlkit_scanner.scanner.Scanner
import com.dns_technologies.mlkit_scanner.scanner.ScannerView
import android.app.Activity
import android.content.Context
import android.os.Handler
import androidx.lifecycle.Lifecycle
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.embedding.engine.plugins.lifecycle.HiddenLifecycleReference
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions

internal class MlkitScannerPluginCaptureTest {
    @Test
    fun `invalid arguments are reported by plugin error handling`() = runBlocking {
        val permissionGateway = mock(PermissionGateway::class.java)
        val scannerDevice = RecordingScanner()
        val result = mock(MethodChannel.Result::class.java)

        plugin(scannerDevice, permissionGateway).onMethodCall(
            MethodCall("captureCamera", emptyMap<String, Any>()),
            result,
        )

        verify(result).error(
            PluginError.InvalidArguments.errorCode,
            PluginError.InvalidArguments.message,
            null,
        )
        assertEquals(emptyList<String>(), scannerDevice.calls)
        verify(permissionGateway, never()).requestCameraPermission()
        Unit
    }

    @Test
    fun `capture delegates one permission-aware transaction to device`() = runBlocking {
        val permissionGateway = grantedPermissionGateway()
        val scannerDevice = RecordingScanner()
        val result = mock(MethodChannel.Result::class.java)

        plugin(scannerDevice, permissionGateway).onMethodCall(
            MethodCall("captureCamera", mapOf("viewId" to VIEW_ID, "configuration" to CONFIGURATION)),
            result,
        )

        assertEquals(listOf("capture:$VIEW_ID", "complete:$VIEW_ID"), scannerDevice.calls)
        verify(result).success(true)
    }

    @Test
    fun `capture is canceled successfully when platform view device is already gone`() = runBlocking {
        val permissionGateway = mock(PermissionGateway::class.java)
        val result = mock(MethodChannel.Result::class.java)

        plugin(
            scannerDevice = null,
            permissionGateway = permissionGateway,
            commandScope = CoroutineScope(Dispatchers.Unconfined),
        ).onMethodCall(
            MethodCall("captureCamera", mapOf("viewId" to VIEW_ID, "configuration" to CONFIGURATION)),
            result,
        )

        verify(result).success(true)
        verify(permissionGateway, never()).requestCameraPermission()
        Unit
    }

    @Test
    fun `denied permission from capture transaction keeps stable error`() = runBlocking {
        val permissionGateway = mock(PermissionGateway::class.java)
        doReturn(false).`when`(permissionGateway).requestCameraPermission()
        val scannerDevice = RecordingScanner()
        val result = mock(MethodChannel.Result::class.java)

        plugin(scannerDevice, permissionGateway).onMethodCall(
            MethodCall("captureCamera", mapOf("viewId" to VIEW_ID, "configuration" to CONFIGURATION)),
            result,
        )

        assertEquals(listOf("capture:$VIEW_ID"), scannerDevice.calls)
        verify(result).error(
            PluginError.AuthorizationCameraError.errorCode,
            PluginError.AuthorizationCameraError.message,
            null,
        )
    }

    @Test
    fun `unexpected capture failure uses plugin unknown error mapping`() = runBlocking {
        val failure = IllegalStateException("CameraX bind failed")
        val scannerDevice = RecordingScanner().apply {
            completeError = failure
        }
        val result = mock(MethodChannel.Result::class.java)
        val details = ArgumentCaptor.forClass(Map::class.java)

        plugin(scannerDevice, grantedPermissionGateway()).onMethodCall(
            MethodCall("captureCamera", mapOf("viewId" to VIEW_ID, "configuration" to CONFIGURATION)),
            result,
        )

        verify(result).error(
            eq(PluginError.UnknownError.errorCode),
            eq(PluginError.UnknownError.message),
            details.capture(),
        )
        assertEquals(failure.message, details.value["message"])
        assertEquals(failure.stackTraceToString(), details.value["stackTrace"])
        verifyNoMoreInteractions(result)
    }

    @Test
    fun `typed camera initialization failure is preserved`() = runBlocking {
        val scannerDevice = RecordingScanner().apply {
            completeError = PluginError.CameraSessionDisposed
        }
        val result = mock(MethodChannel.Result::class.java)

        plugin(scannerDevice, grantedPermissionGateway()).onMethodCall(
            MethodCall("captureCamera", mapOf("viewId" to VIEW_ID, "configuration" to CONFIGURATION)),
            result,
        )

        verify(result).error(
            PluginError.CameraSessionDisposed.errorCode,
            PluginError.CameraSessionDisposed.message,
            null,
        )
        verifyNoMoreInteractions(result)
    }

    @Test
    fun `camera control failure preserves structured details`() = runBlocking {
        val failure = PluginError.CameraControlError(
            operation = CameraControlOperation.AWAIT_OPEN,
            viewId = VIEW_ID,
            cause = IllegalStateException("camera disconnected"),
            cameraStateErrorCode = 3,
        )
        val scannerDevice = RecordingScanner().apply { completeError = failure }
        val result = mock(MethodChannel.Result::class.java)

        plugin(scannerDevice, grantedPermissionGateway()).onMethodCall(
            MethodCall("captureCamera", mapOf("viewId" to VIEW_ID, "configuration" to CONFIGURATION)),
            result,
        )

        verify(result).error(failure.errorCode, failure.message, failure.details)
        verifyNoMoreInteractions(result)
    }

    @Test
    fun `capture reports success only after the device completes`() = runBlocking {
        val completion = CompletableDeferred<Unit>()
        val scannerDevice = RecordingScanner().apply { captureCompletion = completion }
        val result = mock(MethodChannel.Result::class.java)

        plugin(scannerDevice, grantedPermissionGateway()).onMethodCall(
            MethodCall("captureCamera", mapOf("viewId" to VIEW_ID, "configuration" to CONFIGURATION)),
            result,
        )

        verifyNoInteractions(result)
        completion.complete(Unit)

        verify(result).success(true)
        verifyNoMoreInteractions(result)
    }

    @Test
    fun `capture failure after suspension uses plugin error handling`() = runBlocking {
        val completion = CompletableDeferred<Unit>()
        val scannerDevice = RecordingScanner().apply { captureCompletion = completion }
        val result = mock(MethodChannel.Result::class.java)
        val details = ArgumentCaptor.forClass(Map::class.java)
        val failure = IllegalStateException("CameraX failed after suspension")

        plugin(scannerDevice, grantedPermissionGateway()).onMethodCall(
            MethodCall("captureCamera", mapOf("viewId" to VIEW_ID, "configuration" to CONFIGURATION)),
            result,
        )

        verifyNoInteractions(result)
        completion.completeExceptionally(failure)

        verify(result).error(
            eq(PluginError.UnknownError.errorCode),
            eq(PluginError.UnknownError.message),
            details.capture(),
        )
        assertEquals(failure.message, details.value["message"])
        // A suspended coroutine may add recovery frames to the original stack trace.
        val stackTrace = details.value["stackTrace"] as String
        assertTrue(stackTrace.contains(failure.toString()))
        assertTrue(stackTrace.contains(MlkitScannerPluginCaptureTest::class.java.name))
        verifyNoMoreInteractions(result)
    }

    @Test
    fun `capture cancellation completes the live channel reply and cancels the command`() = runBlocking {
        val completion = CompletableDeferred<Unit>()
        val scannerDevice = RecordingScanner().apply { captureCompletion = completion }
        val result = mock(MethodChannel.Result::class.java)
        val commandScope = CoroutineScope(Dispatchers.Unconfined)
        try {
            plugin(
                scannerDevice = scannerDevice,
                permissionGateway = grantedPermissionGateway(),
                commandScope = commandScope,
            ).onMethodCall(MethodCall("captureCamera", mapOf("viewId" to VIEW_ID, "configuration" to CONFIGURATION)), result)
            val commandJob = requireNotNull(commandScope.coroutineContext[Job]).children.single()

            completion.cancel(CancellationException("capture cancelled"))

            assertTrue(commandJob.isCancelled)
            assertTrue(commandScope.isActive)
            verify(result).error(PluginError.CameraSessionDisposed.errorCode, PluginError.CameraSessionDisposed.message, null)
        } finally {
            commandScope.cancel()
        }
    }

    private suspend fun grantedPermissionGateway(): PermissionGateway =
        mock(PermissionGateway::class.java).also { gateway ->
            doReturn(true).`when`(gateway).requestCameraPermission()
        }

    private fun plugin(
        scannerDevice: RecordingScanner?,
        permissionGateway: PermissionGateway,
        commandScope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined),
    ): MlkitScannerPlugin {
        val plugin = MlkitScannerPlugin(
            mainHandler = mock(Handler::class.java),
            channel = mock(MethodChannel::class.java),
            scannerFactory = { _, _, _ -> requireNotNull(scannerDevice).device },
            commandScope = commandScope,
            permissionGateway = permissionGateway,
        )
        val binding = mock(ActivityPluginBinding::class.java)
        doReturn(mock(Activity::class.java)).`when`(binding).activity
        doReturn(HiddenLifecycleReference(mock(Lifecycle::class.java))).`when`(binding).lifecycle
        plugin.attach(binding)
        if (scannerDevice != null) {
            val view = mock(ScannerView::class.java)
            doReturn(VIEW_ID).`when`(view).viewId
            doReturn(mock(Context::class.java)).`when`(view).context
            @Suppress("UNCHECKED_CAST")
            val views = plugin.javaClass.getDeclaredField("views").apply { isAccessible = true }
                .get(plugin) as MutableMap<Int, ScannerView>
            views[VIEW_ID] = view
        }
        return plugin
    }

    private class RecordingScanner {
        val device: Scanner = mock(Scanner::class.java, org.mockito.AdditionalAnswers.delegatesTo<Any>(this))
        val calls = mutableListOf<String>()
        var completeError: Exception? = null
        var captureCompletion: CompletableDeferred<Unit>? = null

        fun attachActivity(lifecycle: Lifecycle) = Unit
        fun select(view: ScannerView) = Unit

        suspend fun capture(configuration: com.dns_technologies.mlkit_scanner.scanner.ScannerConfiguration, permission: suspend () -> Boolean) {
            val viewId = VIEW_ID
            calls += "capture:$viewId"
            if (!permission()) throw PluginError.AuthorizationCameraError
            captureCompletion?.await()
            calls += "complete:$viewId"
            completeError?.let { throw it }
        }
    }

    private companion object {
        const val VIEW_ID = 42
        val CONFIGURATION = mapOf("zoomRatio" to 1.0, "torchEnabled" to false, "cropRect" to null, "scanEnabled" to false, "scanDelay" to 0)
    }
}
