package com.dns_technologies.mlkit_scanner.commands

import com.dns_technologies.mlkit_scanner.PluginError
import com.dns_technologies.mlkit_scanner.scanner.Scanner
import com.dns_technologies.mlkit_scanner.scanner.models.RecognizeVisorCropRect
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.mockito.Mockito.*
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume

internal class UpdateCameraSettingsCommandTest {
    private val scanner = mock(Scanner::class.java)
    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val command = UpdateCameraSettingsCommand({ scanner }, scope)

    @Test
    fun `applies all supplied settings with one acknowledgment`() = runBlocking<Unit> {
        val reply = mock(MethodChannel.Result::class.java)
        command.execute(MethodCall("updateCameraSettings", mapOf(
            "zoomRatio" to 2.5, "torchEnabled" to true, "cropRect" to mapOf("scaleWidth" to 0.5),
        )), reply)
        val order = inOrder(scanner, reply)
        order.verify(scanner).setCropArea(RecognizeVisorCropRect(scaleWidth = 0.5))
        order.verify(scanner).setZoomRatio(2.5F)
        order.verify(scanner).setTorch(true)
        order.verify(reply).success(true)
        verifyNoMoreInteractions(scanner, reply)
    }

    @Test
    fun `omitted fields are untouched and false torch is applied`() = runBlocking<Unit> {
        val reply = mock(MethodChannel.Result::class.java)
        command.execute(MethodCall("updateCameraSettings", mapOf("torchEnabled" to false)), reply)
        verify(scanner).setTorch(false)
        verifyNoMoreInteractions(scanner)
        verify(reply).success(true)
    }

    @Test
    fun `malformed batch is rejected before any control runs`() {
        val invalid = listOf(
            null,
            mapOf("zoomRatio" to 2.0, "torchEnabled" to 1),
            mapOf("zoomRatio" to 2.0, "cropRect" to "bad"),
            mapOf("zoomRatio" to "2.0"),
            mapOf("zoomRatio" to Double.NaN),
            mapOf("zoomRatio" to Double.MAX_VALUE),
            mapOf("zoomRatio" to true),
            mapOf("torchEnabled" to null),
            mapOf("cropRect" to null),
        )
        for (arguments in invalid) {
            val reply = mock(MethodChannel.Result::class.java)
            command.execute(MethodCall("updateCameraSettings", arguments), reply)
            verify(reply).error(PluginError.InvalidArguments.errorCode, PluginError.InvalidArguments.message, null)
            verifyNoMoreInteractions(reply)
        }
        verifyNoInteractions(scanner)
    }

    @Test
    fun `reply waits for the last asynchronous control`() = runBlocking<Unit> {
        lateinit var torch: Continuation<Unit>
        doAnswer {
            @Suppress("UNCHECKED_CAST")
            torch = it.rawArguments.last() as Continuation<Unit>
            COROUTINE_SUSPENDED
        }.`when`(scanner).setTorch(true)
        val reply = mock(MethodChannel.Result::class.java)
        command.execute(MethodCall("updateCameraSettings", mapOf("zoomRatio" to 2.0, "torchEnabled" to true)), reply)
        verify(scanner).setZoomRatio(2.0F)
        verify(scanner).setTorch(true)
        verifyNoInteractions(reply)
        torch.resume(Unit)
        verify(reply).success(true)
        verifyNoMoreInteractions(reply)
    }

    @Test
    fun `lease revoked during zoom prevents torch from reaching a replacement owner`() = runBlocking<Unit> {
        lateinit var zoom: Continuation<Unit>
        doAnswer {
            @Suppress("UNCHECKED_CAST")
            zoom = it.rawArguments.last() as Continuation<Unit>
            COROUTINE_SUSPENDED
        }.`when`(scanner).setZoomRatio(2.0F)
        var selected = true
        val scoped = UpdateCameraSettingsCommand({
            if (!selected) throw PluginError.CameraSessionDisposed
            scanner
        }, scope)
        val reply = mock(MethodChannel.Result::class.java)
        scoped.execute(MethodCall("updateCameraSettings", mapOf("zoomRatio" to 2.0, "torchEnabled" to true)), reply)
        verifyNoInteractions(reply)
        selected = false
        zoom.resume(Unit)
        verify(scanner, never()).setTorch(true)
        verify(reply).error(PluginError.CameraSessionDisposed.errorCode, PluginError.CameraSessionDisposed.message, null)
        verifyNoMoreInteractions(reply)
    }

    @Test
    fun `failure after crop stops remaining controls and reports once`() = runBlocking<Unit> {
        doAnswer { throw PluginError.CameraIsNotInitialized }.`when`(scanner).setZoomRatio(2.0F)
        val reply = mock(MethodChannel.Result::class.java)
        command.execute(MethodCall("updateCameraSettings", mapOf(
            "cropRect" to mapOf("scaleWidth" to 0.5), "zoomRatio" to 2.0, "torchEnabled" to true,
        )), reply)
        verify(scanner).setCropArea(RecognizeVisorCropRect(scaleWidth = 0.5))
        verify(scanner, never()).setTorch(true)
        verify(reply).error(PluginError.CameraIsNotInitialized.errorCode, PluginError.CameraIsNotInitialized.message, null)
        verifyNoMoreInteractions(reply)
    }
}
