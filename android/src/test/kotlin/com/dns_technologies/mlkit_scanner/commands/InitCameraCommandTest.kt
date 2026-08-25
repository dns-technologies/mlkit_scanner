package com.dns_technologies.mlkit_scanner.commands

import android.content.Context
import com.dns_technologies.mlkit_scanner.models.ScannerSession
import com.dns_technologies.mlkit_scanner.permissions.PermissionGateway
import com.dns_technologies.mlkit_scanner.scanner.ScannerView
import com.dns_technologies.mlkit_scanner.scanner.models.InitialScannerParameters
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
    fun `missing initial arguments starts camera with default parameters`() = runBlocking {
        val permissionGateway = mock(PermissionGateway::class.java)
        doReturn(true).`when`(permissionGateway).requestPermissions(anyValue())
        val scannerSession = RecordingScannerSession()
        val result = mock(MethodChannel.Result::class.java)
        val command = InitCameraCommand(
            scannerSessionProvider = { scannerSession },
            permissionGateway = permissionGateway,
            commandScope = CoroutineScope(Dispatchers.Unconfined),
        )

        command.execute(MethodCall("initCameraPreview", null), result)

        assertEquals(1, scannerSession.startCalls)
        assertNull(scannerSession.startParameters)
        verify(result).success(true)
    }

    private class RecordingScannerSession : ScannerSession {
        var startCalls = 0
            private set
        var startParameters: InitialScannerParameters? = null
            private set
        override fun createView(context: Context, viewId: Int): ScannerView =
            mock(ScannerView::class.java)

        override suspend fun startCamera(parameters: InitialScannerParameters?) {
            startCalls += 1
            startParameters = parameters
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
        override fun disposeView(viewId: Int?) = Unit
        override fun release() = Unit
    }

    private fun <T> anyValue(): T = any<T>()
}
