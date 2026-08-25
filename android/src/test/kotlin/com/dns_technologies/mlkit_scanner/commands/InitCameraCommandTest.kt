package com.dns_technologies.mlkit_scanner.commands

import android.content.Context
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
        verify(result).success(true)
    }

    private class RecordingScannerSession : ScannerSession {
        var startCalls = 0
            private set
        var startInitialZoom: Double? = null
            private set
        var startInitialCropRect: RecognizeVisorCropRect? = null
            private set
        var startViewId: Int? = null
            private set
        override fun createView(context: Context, viewId: Int): ScannerView =
            mock(ScannerView::class.java)

        override suspend fun startCamera(
            viewId: Int,
            initialZoom: Double?,
            initialCropRect: RecognizeVisorCropRect?,
        ) {
            startCalls += 1
            startViewId = viewId
            startInitialZoom = initialZoom
            startInitialCropRect = initialCropRect
        }

        override fun resumeCamera() = Unit
        override fun pauseCamera() = Unit
        override fun onHostResume() = Unit
        override fun onHostPause() = Unit
        override fun toggleFlashLight() = Unit
        override fun startScan(periodMs: Int) = Unit
        override fun pauseScan() = Unit
        override fun updateScanPeriod(periodMs: Int) = Unit
        override fun setZoom(value: Float) = Unit
        override fun setCropArea(cropRect: RecognizeVisorCropRect) = Unit
        override fun disposeView(viewId: Int) = Unit
        override fun release() = Unit
    }

    private fun <T> anyValue(): T = any<T>()

    private companion object {
        const val VIEW_ID = 42
    }
}
