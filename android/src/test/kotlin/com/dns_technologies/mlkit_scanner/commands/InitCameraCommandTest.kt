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
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify

internal class InitCameraCommandTest {
    @Test
    fun `view identity without initial settings starts camera with default parameters`() = runBlocking {
        val permissionGateway = mock(PermissionGateway::class.java)
        doReturn(true).`when`(permissionGateway).requestPermissions(anyValue())
        val scannerSession = RecordingScannerSession()
        val result = mock(MethodChannel.Result::class.java)
        val command = InitCameraCommand(
            scannerSessionProvider = { scannerSession },
            permissionGateway = permissionGateway,
            commandScope = CoroutineScope(Dispatchers.Unconfined),
        )

        command.execute(
            MethodCall(
                "initCameraPreview",
                mapOf("viewId" to VIEW_ID),
            ),
            result,
        )

        assertEquals(1, scannerSession.startCalls)
        assertEquals(VIEW_ID, scannerSession.startViewId)
        assertNull(scannerSession.startInitialZoom)
        assertNull(scannerSession.startInitialCropRect)
        assertNull(scannerSession.startInitialFlashEnabled)
        verify(result).success(true)
    }

    @Test
    fun `initial zoom and crop are passed directly to scanner session`() = runBlocking {
        val permissionGateway = mock(PermissionGateway::class.java)
        doReturn(true).`when`(permissionGateway).requestPermissions(anyValue())
        val scannerSession = RecordingScannerSession()
        val result = mock(MethodChannel.Result::class.java)
        val command = InitCameraCommand(
            scannerSessionProvider = { scannerSession },
            permissionGateway = permissionGateway,
            commandScope = CoroutineScope(Dispatchers.Unconfined),
        )

        command.execute(
            MethodCall(
                "initCameraPreview",
                mapOf(
                    "viewId" to VIEW_ID,
                    "initialZoom" to 0.4,
                    "initialFlashEnabled" to true,
                    "initialCropRect" to mapOf(
                        "scaleWidth" to 0.5,
                        "scaleHeight" to 0.75,
                        "offsetX" to 0.1,
                        "offsetY" to -0.1,
                    ),
                ),
            ),
            result,
        )

        assertEquals(
            0.4,
            scannerSession.startInitialZoom,
        )
        assertEquals(
            RecognizeVisorCropRect(
                scaleWidth = 0.5,
                scaleHeight = 0.75,
                centerOffsetX = 0.1,
                centerOffsetY = -0.1,
            ),
            scannerSession.startInitialCropRect,
        )
        assertEquals(true, scannerSession.startInitialFlashEnabled)
        verify(result).success(true)
    }

    @Test
    fun `camera initialization failure uses stable initialization error`() = runBlocking {
        val permissionGateway = grantedPermissionGateway()
        val scannerSession = RecordingScannerSession().apply {
            startError = IllegalStateException("CameraX bind failed")
        }
        val result = mock(MethodChannel.Result::class.java)

        command(scannerSession, permissionGateway).execute(
            MethodCall("initCameraPreview", mapOf("viewId" to VIEW_ID)),
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
        val permissionGateway = grantedPermissionGateway()
        val scannerSession = RecordingScannerSession().apply {
            startError = PluginError.CameraSessionDisposed
        }
        val result = mock(MethodChannel.Result::class.java)

        command(scannerSession, permissionGateway).execute(
            MethodCall("initCameraPreview", mapOf("viewId" to VIEW_ID)),
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
    ): InitCameraCommand = InitCameraCommand(
        scannerSessionProvider = { scannerSession },
        permissionGateway = permissionGateway,
        commandScope = CoroutineScope(Dispatchers.Unconfined),
    )

    private class RecordingScannerSession : ScannerSession {
        var startCalls = 0
            private set
        var startInitialZoom: Double? = null
            private set
        var startInitialCropRect: RecognizeVisorCropRect? = null
            private set
        var startViewId: Int? = null
            private set
        var startInitialFlashEnabled: Boolean? = null
            private set
        var startError: Exception? = null
        override fun createView(context: Context, viewId: Int): ScannerView =
            mock(ScannerView::class.java)

        override suspend fun startCamera(
            viewId: Int,
            initialZoom: Double?,
            initialCropRect: RecognizeVisorCropRect?,
            initialFlashEnabled: Boolean?,
        ) {
            startError?.let { throw it }
            startCalls += 1
            startViewId = viewId
            startInitialZoom = initialZoom
            startInitialCropRect = initialCropRect
            startInitialFlashEnabled = initialFlashEnabled
        }

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
        override fun disposeView(viewId: Int) = Unit
        override fun release() = Unit
    }

    private fun <T> anyValue(): T = any<T>()

    private companion object {
        const val VIEW_ID = 42
    }
}
