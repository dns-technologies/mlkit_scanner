package com.dns_technologies.mlkit_scanner.commands

import com.dns_technologies.mlkit_scanner.commands.base.RecordingScannerSession
import com.dns_technologies.mlkit_scanner.commands.base.SessionCall
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify

internal class StartScanCommandTest {
    @Test
    fun `start enables barcode scanning for the addressed view`() {
        val session = RecordingScannerSession()
        val result = mock(MethodChannel.Result::class.java)

        StartScanCommand { session }.execute(call(), result)

        assertEquals(listOf(SessionCall("startScan", VIEW_ID, 250)), session.calls)
        verify(result).success(true)
    }

    @Test
    fun `start completes after its session is gone`() {
        val result = mock(MethodChannel.Result::class.java)

        StartScanCommand { null }.execute(call(), result)

        verify(result).success(true)
    }

    private fun call() = MethodCall(
        "startScan",
        mapOf("viewId" to VIEW_ID, "type" to 0, "delay" to 250),
    )

    private companion object {
        const val VIEW_ID = 42
    }
}
