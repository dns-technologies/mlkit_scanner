package com.dns_technologies.mlkit_scanner.commands

import com.dns_technologies.mlkit_scanner.CameraControlOperation
import com.dns_technologies.mlkit_scanner.PluginError
import com.dns_technologies.mlkit_scanner.commands.base.RecordingScannerSession
import com.dns_technologies.mlkit_scanner.commands.base.SessionCall
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify

internal class SetZoomRatioCommandTest {
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    @Test
    fun `zoom completes only after the camera accepts the value`() {
        val session = RecordingScannerSession().apply {
            zoomCompletion = CompletableDeferred()
        }
        val result = mock(MethodChannel.Result::class.java)

        SetZoomRatioCommand({ session }, scope).execute(call(), result)

        assertEquals(listOf(SessionCall("setZoomRatio", VIEW_ID, 2.5F)), session.calls)
        verify(result, never()).success(anyValue())
        session.zoomCompletion?.complete(Unit)
        verify(result).success(true)
    }

    @Test
    fun `zoom reports a typed camera control failure`() {
        val failure = PluginError.CameraControlError(
            CameraControlOperation.ZOOM,
            VIEW_ID,
            IllegalStateException("rejected"),
        )
        val session = object : RecordingScannerSession() {
            override suspend fun setZoomRatio(viewId: Int, value: Float) {
                throw failure
            }
        }
        val result = mock(MethodChannel.Result::class.java)

        SetZoomRatioCommand({ session }, scope).execute(call(), result)

        verify(result).error(failure.errorCode, failure.message, failure.details)
    }

    @Test
    fun `zoom completes after its session is gone`() {
        val result = mock(MethodChannel.Result::class.java)

        SetZoomRatioCommand({ null }, scope).execute(call(), result)

        verify(result).success(true)
    }

    private fun call() = MethodCall(
        "setZoomRatio",
        mapOf("viewId" to VIEW_ID, "value" to 2.5),
    )

    private fun <T> anyValue(): T = any<T>()

    private companion object {
        const val VIEW_ID = 42
    }
}
