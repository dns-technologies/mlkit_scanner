package com.dns_technologies.mlkit_scanner.commands

import com.dns_technologies.mlkit_scanner.PluginError
import com.dns_technologies.mlkit_scanner.scanner.Scanner
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.mockito.Mockito.*

internal class SetScanDelayCommandTest {
    private val scanner = mock(Scanner::class.java)
    private val command = SetScanDelayCommand { scanner }

    @Test
    fun `applies arguments without view or subscription identifiers`() = runBlocking<Unit> {
        val reply = mock(MethodChannel.Result::class.java)
        command.execute(MethodCall("setScanDelay", mapOf("delay" to 300)), reply)
        verify(scanner).setScanPeriod(300)
        verify(reply).success(true)
    }

    @Test
    fun `rejects malformed arguments before invoking scanner`() {
        for (arguments in listOf(mapOf("delay" to true))) {
            val reply = mock(MethodChannel.Result::class.java)
            command.execute(MethodCall("setScanDelay", arguments), reply)
            verify(reply).error(PluginError.InvalidArguments.errorCode, PluginError.InvalidArguments.message, null)
        }
        verifyNoInteractions(scanner)
    }

    @Test
    fun `delay range remains validated by Dart`() {
        command.execute(MethodCall("setScanDelay", mapOf("delay" to -1)), mock(MethodChannel.Result::class.java))
        verify(scanner).setScanPeriod(-1)
    }
}
