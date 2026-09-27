package com.dns_technologies.mlkit_scanner.commands

import com.dns_technologies.mlkit_scanner.PluginError
import com.dns_technologies.mlkit_scanner.scanner.Scanner
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.mockito.Mockito.*

internal class SetCropAreaCommandTest {
    private val scanner = mock(Scanner::class.java)
    private val command = SetCropAreaCommand { scanner }

    @Test
    fun `applies arguments without view or subscription identifiers`() = runBlocking<Unit> {
        val reply = mock(MethodChannel.Result::class.java)
        command.execute(MethodCall("setCropAreaMethod", mapOf("cropRect" to mapOf("scaleWidth" to 0.5))), reply)
        verify(scanner).setCropArea(com.dns_technologies.mlkit_scanner.scanner.models.RecognizeVisorCropRect(scaleWidth = 0.5))
        verify(reply).success(true)
    }

    @Test
    fun `rejects malformed arguments before invoking scanner`() {
        for (arguments in listOf(mapOf("cropRect" to "bad"))) {
            val reply = mock(MethodChannel.Result::class.java)
            command.execute(MethodCall("setCropAreaMethod", arguments), reply)
            verify(reply).error(PluginError.InvalidArguments.errorCode, PluginError.InvalidArguments.message, null)
        }
        verifyNoInteractions(scanner)
    }
}
