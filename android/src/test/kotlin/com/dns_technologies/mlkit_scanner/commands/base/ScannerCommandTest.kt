package com.dns_technologies.mlkit_scanner.commands.base

import com.dns_technologies.mlkit_scanner.PluginError
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify

internal class ScannerCommandTest {
    @Test
    fun `execute delegates successful synchronous command`() {
        val result = mock(MethodChannel.Result::class.java)
        val command = TestScannerCommand(shouldThrow = false)

        command.execute(MethodCall("test", null), result)

        verify(result).success(true)
    }

    @Test
    fun `execute maps exceptions thrown by synchronous command`() {
        val result = mock(MethodChannel.Result::class.java)
        val command = TestScannerCommand(shouldThrow = true)

        command.execute(MethodCall("test", null), result)

        verify(result).error(
            PluginError.InvalidArguments.errorCode,
            PluginError.InvalidArguments.message,
            null,
        )
    }

    private class TestScannerCommand(
        private val shouldThrow: Boolean,
    ) : ScannerCommand({ null }) {
        override fun executeCommand(call: MethodCall, result: MethodChannel.Result) {
            if (shouldThrow) throw PluginError.InvalidArguments
            success(result)
        }
    }
}
