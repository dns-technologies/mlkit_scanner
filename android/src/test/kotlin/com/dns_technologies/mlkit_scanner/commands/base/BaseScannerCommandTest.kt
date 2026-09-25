package com.dns_technologies.mlkit_scanner.commands.base

import com.dns_technologies.mlkit_scanner.PluginError
import com.dns_technologies.mlkit_scanner.scanner.Scanner
import com.dns_technologies.mlkit_scanner.scanner.ScannerConsumer
import com.dns_technologies.mlkit_scanner.commands.base.DeviceCall
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

/** Records calls on a concrete device double; these values are test assertions, not a production bus. */
internal open class RecordingScanner {
    val isDisposed = false
    val device: Scanner = mock(Scanner::class.java, org.mockito.AdditionalAnswers.delegatesTo<Any>(this))
    val events = mutableListOf<DeviceCall>()
    var zoomCompletion: CompletableDeferred<Unit>? = null
    var torchCompletion: CompletableDeferred<Unit>? = null

    fun releaseCamera() { events += DeviceCall.ReleaseCamera(42) }
    fun startScan(period: Int) { events += DeviceCall.StartScan(42, period) }
    fun pauseScan() { events += DeviceCall.PauseScan(42) }
    fun setScanPeriod(period: Int) { events += DeviceCall.SetScanPeriod(42, period) }
    fun setCropArea(crop: com.dns_technologies.mlkit_scanner.scanner.models.RecognizeVisorCropRect) { events += DeviceCall.SetCrop(42, crop) }
    suspend fun setZoomRatio(ratio: Float) { request(DeviceCall.SetZoom(42, ratio), null) }
    suspend fun setTorch(enabled: Boolean) { request(DeviceCall.ToggleTorch(42), null) }

    open suspend fun request(event: DeviceCall.Request, permission: (suspend () -> Boolean)?) {
        events += event
        when (event) {
            is DeviceCall.SetZoom -> zoomCompletion?.await()
            is DeviceCall.ToggleTorch -> torchCompletion?.await()
        }
    }
}

/** Expected device calls, including the address supplied to the test's lookup callback. */
internal sealed interface DeviceCall {
    val viewId: Int
    sealed interface Request : DeviceCall
    data class ReleaseCamera(override val viewId: Int) : DeviceCall
    data class StartScan(override val viewId: Int, val periodMs: Int) : DeviceCall
    data class PauseScan(override val viewId: Int) : DeviceCall
    data class SetScanPeriod(override val viewId: Int, val periodMs: Int) : DeviceCall
    data class SetCrop(override val viewId: Int, val crop: com.dns_technologies.mlkit_scanner.scanner.models.RecognizeVisorCropRect) : DeviceCall
    data class SetZoom(override val viewId: Int, val ratio: Float) : Request
    data class ToggleTorch(override val viewId: Int) : Request
}
