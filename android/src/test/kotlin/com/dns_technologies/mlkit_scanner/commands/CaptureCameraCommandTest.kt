package com.dns_technologies.mlkit_scanner.commands

import android.content.Context
import com.dns_technologies.mlkit_scanner.PluginError
import com.dns_technologies.mlkit_scanner.session.ScannerSession
import com.dns_technologies.mlkit_scanner.permissions.PermissionGateway
import com.dns_technologies.mlkit_scanner.scanner.ScannerView
import com.dns_technologies.mlkit_scanner.scanner.models.RecognizeVisorCropRect
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify

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
        verify(permissionGateway, never()).requestPermissions(anyValue())
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
        verify(permissionGateway, never()).requestPermissions(anyValue())
        Unit
    }

    @Test
    fun `denied permission from capture transaction keeps stable error`() = runBlocking {
        val permissionGateway = mock(PermissionGateway::class.java)
        doReturn(false).`when`(permissionGateway).requestPermissions(anyValue())
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
    fun `camera initialization failure uses stable initialization error`() = runBlocking {
        val scannerSession = RecordingScannerSession().apply {
            completeError = IllegalStateException("CameraX bind failed")
        }
        val result = mock(MethodChannel.Result::class.java)

        command(scannerSession, grantedPermissionGateway()).execute(
            MethodCall("captureCamera", mapOf("viewId" to VIEW_ID)),
            result,
        )

        verify(result).error(
            PluginError.InitCameraError.errorCode,
            PluginError.InitCameraError.message,
            null,
        )
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
    }

    private suspend fun grantedPermissionGateway(): PermissionGateway =
        mock(PermissionGateway::class.java).also { gateway ->
            doReturn(true).`when`(gateway).requestPermissions(anyValue())
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

    private fun <T> anyValue(): T = any<T>()

    private companion object {
        const val VIEW_ID = 42
    }
}
