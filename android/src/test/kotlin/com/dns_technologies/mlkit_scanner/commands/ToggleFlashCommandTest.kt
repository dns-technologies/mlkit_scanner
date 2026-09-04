package com.dns_technologies.mlkit_scanner.commands

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

internal class ToggleFlashCommandTest {
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    @Test
    fun `toggle completes only after the camera accepts the torch state`() {
        val session = RecordingScannerSession().apply {
            torchCompletion = CompletableDeferred()
        }
        val result = mock(MethodChannel.Result::class.java)

        ToggleFlashCommand({ session }, scope).execute(call(), result)

        assertEquals(listOf(SessionCall("toggleFlashLight", VIEW_ID)), session.calls)
        verify(result, never()).success(anyValue())
        session.torchCompletion?.complete(Unit)
        verify(result).success(true)
    }

    @Test
    fun `toggle completes after its session is gone`() {
        val result = mock(MethodChannel.Result::class.java)

        ToggleFlashCommand({ null }, scope).execute(call(), result)

        verify(result).success(true)
    }

    private fun call() = MethodCall("toggleFlash", mapOf("viewId" to VIEW_ID))

    private fun <T> anyValue(): T = any<T>()

    private companion object {
        const val VIEW_ID = 42
    }
}
