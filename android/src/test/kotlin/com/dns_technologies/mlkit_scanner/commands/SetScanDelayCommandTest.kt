package com.dns_technologies.mlkit_scanner.commands

import com.dns_technologies.mlkit_scanner.commands.base.RecordingScannerSession
import com.dns_technologies.mlkit_scanner.commands.base.SessionCall
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify

internal class SetScanDelayCommandTest {
    @Test
    fun `delay updates the addressed view scan period`() {
        val session = RecordingScannerSession()
        val result = mock(MethodChannel.Result::class.java)

        SetScanDelayCommand { session }.execute(call(), result)

        assertEquals(listOf(SessionCall("updateScanPeriod", VIEW_ID, 250)), session.calls)
        verify(result).success(true)
    }

    @Test
    fun `delay is retained as superseded work after its session is gone`() {
        val result = mock(MethodChannel.Result::class.java)

        SetScanDelayCommand { null }.execute(call(), result)

        verify(result).success(true)
    }

    private fun call() = MethodCall(
        "setScanDelay",
        mapOf("viewId" to VIEW_ID, "delay" to 250),
    )

    private companion object {
        const val VIEW_ID = 42
    }
}
