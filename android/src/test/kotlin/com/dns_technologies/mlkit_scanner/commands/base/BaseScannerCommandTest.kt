package com.dns_technologies.mlkit_scanner.commands.base

import android.content.Context
import androidx.lifecycle.Lifecycle
import com.dns_technologies.mlkit_scanner.PluginError
import com.dns_technologies.mlkit_scanner.models.ScannerSession
import com.dns_technologies.mlkit_scanner.scanner.ScannerView
import com.dns_technologies.mlkit_scanner.scanner.models.RecognizeVisorCropRect
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify

internal class BaseScannerCommandTest {
    @Test
    fun `success completes the Flutter result with true`() {
        val result = mock(MethodChannel.Result::class.java)

        TestCommand().completeSuccessfully(result)

        verify(result).success(true)
    }

    @Test
    fun `typed plugin errors preserve their channel contract`() {
        val result = mock(MethodChannel.Result::class.java)

        TestCommand().completeWith(result, PluginError.InvalidArguments)

        verify(result).error(
            PluginError.InvalidArguments.errorCode,
            PluginError.InvalidArguments.message,
            null,
        )
    }

    @Test
    fun `unexpected exceptions are mapped to an actionable unknown error`() {
        val result = mock(MethodChannel.Result::class.java)
        val details = ArgumentCaptor.forClass(Map::class.java)

        TestCommand().completeWith(result, IllegalStateException("broken"))

        verify(result).error(
            eq(PluginError.UnknownError.errorCode),
            eq(PluginError.UnknownError.message),
            details.capture(),
        )
        assertEquals("broken", details.value["message"])
        assertTrue((details.value["stackTrace"] as String).isNotBlank())
    }

    private class TestCommand : ScannerCommand({ null }) {
        override fun executeCommand(
            call: MethodCall,
            result: MethodChannel.Result,
        ) = Unit

        fun completeSuccessfully(result: MethodChannel.Result) = success(result)

        fun completeWith(result: MethodChannel.Result, error: Exception) =
            reportError(result, error)
    }
}

internal data class SessionCall(
    val name: String,
    val viewId: Int? = null,
    val value: Any? = null,
)

internal open class RecordingScannerSession : ScannerSession {
    val calls = mutableListOf<SessionCall>()
    var zoomCompletion: CompletableDeferred<Unit>? = null
    var torchCompletion: CompletableDeferred<Unit>? = null

    override fun createView(
        context: Context,
        viewId: Int,
        initialZoomRatio: Double?,
        initialCropRect: RecognizeVisorCropRect?,
        initialFlashEnabled: Boolean?,
    ): ScannerView = error("createView is not used by command tests")

    override suspend fun captureCamera(
        viewId: Int,
        requestCameraPermission: suspend () -> Boolean,
    ) {
        calls += SessionCall("captureCamera", viewId)
    }

    override fun releaseCamera(viewId: Int) {
        calls += SessionCall("releaseCamera", viewId)
    }

    override fun resumeCamera(viewId: Int) {
        calls += SessionCall("resumeCamera", viewId)
    }

    override fun pauseCamera(viewId: Int) {
        calls += SessionCall("pauseCamera", viewId)
    }

    override fun attachHostLifecycle(lifecycle: Lifecycle) = Unit

    override fun detachHostLifecycle() = Unit

    override suspend fun toggleFlashLight(viewId: Int) {
        calls += SessionCall("toggleFlashLight", viewId)
        torchCompletion?.await()
    }

    override fun startScan(viewId: Int, periodMs: Int) {
        calls += SessionCall("startScan", viewId, periodMs)
    }

    override fun pauseScan(viewId: Int) {
        calls += SessionCall("pauseScan", viewId)
    }

    override fun updateScanPeriod(viewId: Int, periodMs: Int) {
        calls += SessionCall("updateScanPeriod", viewId, periodMs)
    }

    open override suspend fun setZoomRatio(viewId: Int, value: Float) {
        calls += SessionCall("setZoomRatio", viewId, value)
        zoomCompletion?.await()
    }

    override fun setCropArea(viewId: Int, cropRect: RecognizeVisorCropRect) {
        calls += SessionCall("setCropArea", viewId, cropRect)
    }

    override fun release() {
        calls += SessionCall("release")
    }
}
