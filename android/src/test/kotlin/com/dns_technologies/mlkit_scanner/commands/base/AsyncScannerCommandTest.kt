package com.dns_technologies.mlkit_scanner.commands.base

import com.dns_technologies.mlkit_scanner.PluginError
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify

internal class AsyncScannerCommandTest {
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    @Test
    fun `execute completes only after suspend command finishes`() {
        val result = mock(MethodChannel.Result::class.java)
        val completion = CompletableDeferred<Unit>()
        val command = TestAsyncScannerCommand(scope, completion, null)

        command.execute(MethodCall("test", null), result)
        verify(result, never()).success(anyValue())

        completion.complete(Unit)
        verify(result).success(true)
    }

    @Test
    fun `execute maps exceptions from suspend command`() {
        val result = mock(MethodChannel.Result::class.java)
        val command = TestAsyncScannerCommand(
            scope,
            CompletableDeferred(Unit),
            PluginError.CameraSessionDisposed,
        )

        command.execute(MethodCall("test", null), result)

        verify(result).error(
            PluginError.CameraSessionDisposed.errorCode,
            PluginError.CameraSessionDisposed.message,
            null,
        )
    }

    private class TestAsyncScannerCommand(
        scope: CoroutineScope,
        private val completion: CompletableDeferred<Unit>,
        private val error: Exception?,
    ) : AsyncScannerCommand({ null }, scope) {
        override suspend fun executeSuspendCommand(
            call: MethodCall,
            result: MethodChannel.Result,
        ) {
            completion.await()
            error?.let { throw it }
            success(result)
        }
    }

    private fun <T> anyValue(): T = any<T>()
}
