package com.dns_technologies.mlkit_scanner.commands

import com.dns_technologies.mlkit_scanner.scanner.Scanner
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.mockito.Mockito.*

internal class CancelScanCommandTest {
    private val scanner = mock(Scanner::class.java)
    private val command = CancelScanCommand { scanner }

    @Test
    fun `applies arguments without view or subscription identifiers`() = runBlocking<Unit> {
        val reply = mock(MethodChannel.Result::class.java)
        command.execute(MethodCall("cancelScan", null), reply)
        verify(scanner).pauseScan()
        verify(reply).success(true)
    }
}
