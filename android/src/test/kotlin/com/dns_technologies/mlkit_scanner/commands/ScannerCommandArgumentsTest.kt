package com.dns_technologies.mlkit_scanner.commands

import com.dns_technologies.mlkit_scanner.PluginError
import com.dns_technologies.mlkit_scanner.scanner.Scanner
import com.dns_technologies.mlkit_scanner.scanner.ScannerConfiguration
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.mockito.Mockito.*

internal class ScannerCommandArgumentsTest {
    private val scope = CoroutineScope(Dispatchers.Unconfined)
    @Test
    fun `point commands parse settings without view or scan identifiers`() = runBlocking<Unit> {
        val scanner = mock(Scanner::class.java)
        fun result() = mock(MethodChannel.Result::class.java)
        SetZoomRatioCommand({ scanner }, scope).execute(MethodCall("setZoomRatio", mapOf("value" to 2.5)), result())
        ToggleFlashCommand({ scanner }, scope).execute(MethodCall("toggleFlash", mapOf("value" to true)), result())
        StartScanCommand { scanner }.execute(MethodCall("startScan", mapOf("delay" to 250)), result())
        SetScanDelayCommand { scanner }.execute(MethodCall("setScanDelay", mapOf("delay" to 300)), result())
        SetCropAreaCommand { scanner }.execute(MethodCall("setCropArea", mapOf("cropRect" to mapOf("scaleWidth" to 0.5))), result())
        CancelScanCommand { scanner }.execute(MethodCall("cancelScan", null), result())
        verify(scanner).setZoomRatio(2.5F)
        verify(scanner).setTorch(true)
        verify(scanner).startScan(250)
        verify(scanner).setScanPeriod(300)
        verify(scanner).setCropArea(com.dns_technologies.mlkit_scanner.scanner.models.RecognizeVisorCropRect(scaleWidth = 0.5))
        verify(scanner).pauseScan()
    }

    @Test
    fun `point commands reject malformed values before invoking scanner`() {
        val scanner = mock(Scanner::class.java)
        val cases = listOf(
            "setZoomRatio" to null, "setZoomRatio" to mapOf("value" to "bad"),
            "toggleFlash" to mapOf("value" to 1),
            "startScan" to mapOf("type" to 0, "delay" to 0.5),
            "setScanDelay" to mapOf("delay" to true),
            "setCropArea" to mapOf("cropRect" to "bad"),
        )
        for ((method, arguments) in cases) {
            val result = mock(MethodChannel.Result::class.java)
            val call = MethodCall(method, arguments)
            when (method) {
                "setZoomRatio" -> SetZoomRatioCommand({ scanner }, scope).execute(call, result)
                "toggleFlash" -> ToggleFlashCommand({ scanner }, scope).execute(call, result)
                "startScan" -> StartScanCommand { scanner }.execute(call, result)
                "setScanDelay" -> SetScanDelayCommand { scanner }.execute(call, result)
                "setCropArea" -> SetCropAreaCommand { scanner }.execute(call, result)
            }
            verify(result).error(PluginError.InvalidArguments.errorCode, PluginError.InvalidArguments.message, null)
        }
        verifyNoInteractions(scanner)
    }

    @Test
    fun `delay range is owned by Dart not duplicated in commands`() {
        val scanner = mock(Scanner::class.java)
        StartScanCommand { scanner }.execute(MethodCall("startScan", mapOf("type" to 0, "delay" to -1)), mock(MethodChannel.Result::class.java))
        SetScanDelayCommand { scanner }.execute(MethodCall("setScanDelay", mapOf("delay" to -2)), mock(MethodChannel.Result::class.java))
        verify(scanner).startScan(-1)
        verify(scanner).setScanPeriod(-2)
    }
}
