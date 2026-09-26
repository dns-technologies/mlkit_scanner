package com.dns_technologies.mlkit_scanner.commands

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
    fun `rejects malformed arguments before invoking scanner`() {
        for (arguments in listOf(null, mapOf("value" to "bad"))) {
            val reply = mock(MethodChannel.Result::class.java)
            command.execute(MethodCall("setZoomRatio", arguments), reply)
            verify(reply).error(PluginError.InvalidArguments.errorCode, PluginError.InvalidArguments.message, null)
        }
        verifyNoInteractions(scanner)
    }
}
