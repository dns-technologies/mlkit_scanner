package com.dns_technologies.mlkit_scanner.commands

import android.content.Context
import androidx.lifecycle.Lifecycle
import com.dns_technologies.mlkit_scanner.PluginError
import com.dns_technologies.mlkit_scanner.models.ScannerSession
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
import org.mockito.Mockito.verify

internal class CaptureCameraCommandTest {
    @Test
    fun `missing session keeps stable initialization state error`() = runBlocking {
        val result = mock(MethodChannel.Result::class.java)
        val command = CaptureCameraCommand(
            scannerSessionProvider = { null },
            permissionGateway = grantedPermissionGateway(),
            commandScope = CoroutineScope(Dispatchers.Unconfined),
        )

        command.execute(
            MethodCall("captureCamera", mapOf("viewId" to VIEW_ID)),
            result,
        )

        verify(result).error(
            PluginError.CameraIsNotInitialized.errorCode,
            PluginError.CameraIsNotInitialized.message,
            null,
        )
    }

    @Test
    fun `invalid view id keeps stable invalid arguments error`() = runBlocking {
        val scannerSession = RecordingScannerSession()
        val result = mock(MethodChannel.Result::class.java)

        command(scannerSession, grantedPermissionGateway()).execute(
            MethodCall("captureCamera", mapOf("viewId" to -1)),
            result,
        )

        assertEquals(emptyList<String>(), scannerSession.calls)
        verify(result).error(
            PluginError.InvalidArguments.errorCode,
            PluginError.InvalidArguments.message,
            null,
        )
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
            initialZoom: Double?,
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
        override fun attachHostLifecycle(lifecycle: Lifecycle) = Unit
        override fun detachHostLifecycle() = Unit
        override suspend fun toggleFlashLight(viewId: Int) = Unit
        override fun startScan(viewId: Int, periodMs: Int) = Unit
        override fun pauseScan(viewId: Int) = Unit
        override fun updateScanPeriod(viewId: Int, periodMs: Int) = Unit
        override suspend fun setZoom(viewId: Int, value: Float) = Unit
        override fun setCropArea(viewId: Int, cropRect: RecognizeVisorCropRect) = Unit
        override fun release() = Unit
    }

    private fun <T> anyValue(): T = any<T>()

    private companion object {
        const val VIEW_ID = 42
    }
}
