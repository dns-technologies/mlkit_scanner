package com.dns_technologies.mlkit_scanner.commands

import com.dns_technologies.mlkit_scanner.PluginError
import com.dns_technologies.mlkit_scanner.scanner.Scanner
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.mockito.Mockito.*

internal class StartScanCommandTest {
    private val scanner = mock(Scanner::class.java)
    private val command = StartScanCommand { scanner }

    @Test
    fun `applies arguments without view or subscription identifiers`() = runBlocking<Unit> {
        val reply = mock(MethodChannel.Result::class.java)
        command.execute(MethodCall("startScan", mapOf("delay" to 250)), reply)
        verify(scanner).startScan(250)
        verify(reply).success(true)
    }

    @Test
    fun `rejects malformed arguments before invoking scanner`() {
        for (arguments in listOf(mapOf("type" to 0, "delay" to 0.5))) {
            val reply = mock(MethodChannel.Result::class.java)
            command.execute(MethodCall("startScan", arguments), reply)
            verify(reply).error(PluginError.InvalidArguments.errorCode, PluginError.InvalidArguments.message, null)
        }
        verifyNoInteractions(scanner)
    }

    @Test
    fun `delay range remains validated by Dart`() {
        command.execute(MethodCall("startScan", mapOf("delay" to -1)), mock(MethodChannel.Result::class.java))
        verify(scanner).startScan(-1)
    }
}
