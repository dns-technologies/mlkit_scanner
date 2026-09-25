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

internal class ToggleFlashCommandTest {
    private val scanner = mock(Scanner::class.java)
    private val command = ToggleFlashCommand({ scanner }, CoroutineScope(Dispatchers.Unconfined))

    @Test
    fun `applies arguments without view or subscription identifiers`() = runBlocking<Unit> {
        val reply = mock(MethodChannel.Result::class.java)
        command.execute(MethodCall("toggleFlash", mapOf("value" to true)), reply)
        verify(scanner).setTorch(true)
        verify(reply).success(true)
    }

    @Test
    fun `rejects malformed arguments before invoking scanner`() {
        for (arguments in listOf(mapOf("value" to 1))) {
            val reply = mock(MethodChannel.Result::class.java)
            command.execute(MethodCall("toggleFlash", arguments), reply)
            verify(reply).error(PluginError.InvalidArguments.errorCode, PluginError.InvalidArguments.message, null)
        }
        verifyNoInteractions(scanner)
    }
}
