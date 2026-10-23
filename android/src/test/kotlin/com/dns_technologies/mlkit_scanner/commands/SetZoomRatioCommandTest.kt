package com.dns_technologies.mlkit_scanner.commands

import com.dns_technologies.mlkit_scanner.commands.base.verifyError
import com.dns_technologies.mlkit_scanner.PluginError
import com.dns_technologies.mlkit_scanner.scanner.Scanner
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.mockito.Mockito.*

internal class SetZoomRatioCommandTest {
    private val scanner = mock(Scanner::class.java)
    private val command = SetZoomRatioCommand({ scanner }, CoroutineScope(Dispatchers.Unconfined))

    @Test
    fun `applies arguments without view or subscription identifiers`() = runBlocking<Unit> {
        val reply = mock(MethodChannel.Result::class.java)
        command.execute(MethodCall("setZoomRatio", mapOf("value" to 2.5)), reply)
        verify(scanner).setZoomRatio(2.5F)
        verify(reply).success(true)
    }

    @Test
    fun `camera control failure preserves its typed channel error and completes once`() = runBlocking<Unit> {
        val failure = PluginError.CameraError("Failed to set camera zoom ratio to 2.5", viewId = 42)
        doAnswer { throw failure }.`when`(scanner).setZoomRatio(2.5F)
        val reply = mock(MethodChannel.Result::class.java)

        command.execute(MethodCall("setZoomRatio", mapOf("value" to 2.5)), reply)

        verifyError(reply, failure)
        verifyNoMoreInteractions(reply)
        verify(scanner).setZoomRatio(2.5F)
    }

    @Test
    fun `rejects malformed arguments before invoking scanner`() {
        for (arguments in listOf(null, mapOf("value" to "bad"))) {
            val reply = mock(MethodChannel.Result::class.java)
            command.execute(MethodCall("setZoomRatio", arguments), reply)
            verifyError(reply, PluginError.InvalidArguments)
        }
        verifyNoInteractions(scanner)
    }
}
