package com.dns_technologies.mlkit_scanner.commands

import android.content.Context
import com.dns_technologies.mlkit_scanner.CameraControlOperation
import com.dns_technologies.mlkit_scanner.PluginError
import com.dns_technologies.mlkit_scanner.session.ScannerSession
import com.dns_technologies.mlkit_scanner.permissions.PermissionGateway
import com.dns_technologies.mlkit_scanner.scanner.ScannerView
import com.dns_technologies.mlkit_scanner.scanner.models.RecognizeVisorCropRect
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

internal class CaptureCameraCommandTest {
    @Test
    fun `invalid arguments are reported by inherited async error handling`() = runBlocking {
        val permissionGateway = mock(PermissionGateway::class.java)
        val scannerSession = RecordingScannerSession()
        val result = mock(MethodChannel.Result::class.java)

        command(scannerSession, permissionGateway).execute(
            MethodCall("captureCamera", emptyMap<String, Any>()),
            result,
        )

        verify(result).error(
            PluginError.InvalidArguments.errorCode,
            PluginError.InvalidArguments.message,
            null,
        )
        assertEquals(emptyList<String>(), scannerSession.calls)
        verify(permissionGateway, never()).requestCameraPermission()
        Unit
    }

    @Test
    fun `capture delegates one permission-aware transaction to session`() = runBlocking {
        val permissionGateway = grantedPermissionGateway()
        val scannerSession = RecordingScannerSession()
        val result = mock(MethodChannel.Result::class.java)

        command(scannerSession, permissionGateway).execute(
            MethodCall("captureCamera", mapOf("viewId" to VIEW_ID)),
            result,
        )

        assertEquals(listOf("capture:$VIEW_ID", "complete:$VIEW_ID"), scannerSession.calls)
        verify(result).success(true)
    }

    @Test
    fun `capture is canceled successfully when platform view session is already gone`() = runBlocking {
        val permissionGateway = mock(PermissionGateway::class.java)
        val result = mock(MethodChannel.Result::class.java)

        CaptureCameraCommand(
            scannerSessionProvider = { null },
            permissionGateway = permissionGateway,
            commandScope = CoroutineScope(Dispatchers.Unconfined),
        ).execute(
            MethodCall("captureCamera", mapOf("viewId" to VIEW_ID)),
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
        val scannerSession = RecordingScannerSession()
        val result = mock(MethodChannel.Result::class.java)

        command(scannerSession, permissionGateway).execute(
            MethodCall("captureCamera", mapOf("viewId" to VIEW_ID)),
            result,
        )

        assertEquals(listOf("capture:$VIEW_ID"), scannerSession.calls)
        verify(result).error(
            PluginError.AuthorizationCameraError.errorCode,
            PluginError.AuthorizationCameraError.message,
            null,
        )
    }

    @Test
    fun `unexpected capture failure uses inherited unknown error mapping`() = runBlocking {
        val failure = IllegalStateException("CameraX bind failed")
        val scannerSession = RecordingScannerSession().apply {
            completeError = failure
        }
        val result = mock(MethodChannel.Result::class.java)
        val details = ArgumentCaptor.forClass(Map::class.java)

        command(scannerSession, grantedPermissionGateway()).execute(
            MethodCall("captureCamera", mapOf("viewId" to VIEW_ID)),
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
        val scannerSession = RecordingScannerSession().apply {
            completeError = PluginError.CameraSessionDisposed
        }
        val result = mock(MethodChannel.Result::class.java)

        command(scannerSession, grantedPermissionGateway()).execute(
            MethodCall("captureCamera", mapOf("viewId" to VIEW_ID)),
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
        val scannerSession = RecordingScannerSession().apply { completeError = failure }
        val result = mock(MethodChannel.Result::class.java)

        command(scannerSession, grantedPermissionGateway()).execute(
            MethodCall("captureCamera", mapOf("viewId" to VIEW_ID)),
            result,
        )

        verify(result).error(failure.errorCode, failure.message, failure.details)
        verifyNoMoreInteractions(result)
    }

    @Test
    fun `capture reports success only after the session completes`() = runBlocking {
        val completion = CompletableDeferred<Unit>()
        val scannerSession = RecordingScannerSession().apply { captureCompletion = completion }
        val result = mock(MethodChannel.Result::class.java)

        command(scannerSession, grantedPermissionGateway()).execute(
            MethodCall("captureCamera", mapOf("viewId" to VIEW_ID)),
            result,
        )

        verifyNoInteractions(result)
        completion.complete(Unit)

        verify(result).success(true)
        verifyNoMoreInteractions(result)
    }

    @Test
    fun `capture failure after suspension uses inherited error handling`() = runBlocking {
        val completion = CompletableDeferred<Unit>()
        val scannerSession = RecordingScannerSession().apply { captureCompletion = completion }
        val result = mock(MethodChannel.Result::class.java)
        val details = ArgumentCaptor.forClass(Map::class.java)
        val failure = IllegalStateException("CameraX failed after suspension")

        command(scannerSession, grantedPermissionGateway()).execute(
            MethodCall("captureCamera", mapOf("viewId" to VIEW_ID)),
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
        assertTrue(stackTrace.contains(CaptureCameraCommandTest::class.java.name))
        verifyNoMoreInteractions(result)
    }

    @Test
    fun `capture cancellation cancels the command without reporting an error`() = runBlocking {
        val completion = CompletableDeferred<Unit>()
        val scannerSession = RecordingScannerSession().apply { captureCompletion = completion }
        val result = mock(MethodChannel.Result::class.java)
        val commandScope = CoroutineScope(Dispatchers.Unconfined)
        try {
            CaptureCameraCommand(
                scannerSessionProvider = { scannerSession },
                permissionGateway = grantedPermissionGateway(),
                commandScope = commandScope,
            ).execute(MethodCall("captureCamera", mapOf("viewId" to VIEW_ID)), result)
            val commandJob = requireNotNull(commandScope.coroutineContext[Job]).children.single()

            completion.cancel(CancellationException("capture cancelled"))

            assertTrue(commandJob.isCancelled)
            assertTrue(commandScope.isActive)
            verifyNoInteractions(result)
        } finally {
            commandScope.cancel()
        }
    }

    private suspend fun grantedPermissionGateway(): PermissionGateway =
        mock(PermissionGateway::class.java).also { gateway ->
            doReturn(true).`when`(gateway).requestCameraPermission()
        }

    private fun command(
        scannerSession: ScannerSession,
        permissionGateway: PermissionGateway,
    ) = CaptureCameraCommand(
        scannerSessionProvider = { scannerSession },
        permissionGateway = permissionGateway,
        commandScope = CoroutineScope(Dispatchers.Unconfined),
    )

    private class RecordingScannerSession : ScannerSession {
        val calls = mutableListOf<String>()
        var completeError: Exception? = null
        var captureCompletion: CompletableDeferred<Unit>? = null

        override fun createView(
            context: Context,
            viewId: Int,
            initialZoomRatio: Double?,
            initialCropRect: RecognizeVisorCropRect?,
            initialFlashEnabled: Boolean?,
        ): ScannerView = mock(ScannerView::class.java)

        override suspend fun captureCamera(
            viewId: Int,
            requestCameraPermission: suspend () -> Boolean,
        ) {
            calls += "capture:$viewId"
            if (!requestCameraPermission()) throw PluginError.AuthorizationCameraError
            captureCompletion?.await()
            calls += "complete:$viewId"
            completeError?.let { throw it }
        }

        override fun releaseCamera(viewId: Int) = Unit
        override fun resumeCamera(viewId: Int) = Unit
        override fun pauseCamera(viewId: Int) = Unit
        override fun activate() = Unit
        override fun deactivate() = Unit
        override suspend fun toggleFlashLight(viewId: Int) = Unit
        override fun startScan(viewId: Int, periodMs: Int) = Unit
        override fun pauseScan(viewId: Int) = Unit
        override fun updateScanPeriod(viewId: Int, periodMs: Int) = Unit
        override suspend fun setZoomRatio(viewId: Int, value: Float) = Unit
        override fun setCropArea(viewId: Int, cropRect: RecognizeVisorCropRect) = Unit
        override fun release() = Unit
    }

    private companion object {
        const val VIEW_ID = 42
    }
}
