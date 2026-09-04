package com.dns_technologies.mlkit_scanner.commands

import com.dns_technologies.mlkit_scanner.commands.base.RecordingScannerSession
import com.dns_technologies.mlkit_scanner.commands.base.SessionCall
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify

internal class ReleaseCameraCommandTest {
    @Test
    fun `release delegates to the addressed view`() {
        val session = RecordingScannerSession()
        val result = mock(MethodChannel.Result::class.java)

        ReleaseCameraCommand { session }.execute(call(), result)

        assertEquals(listOf(SessionCall("releaseCamera", VIEW_ID)), session.calls)
        verify(result).success(true)
    }

    @Test
    fun `release remains idempotent after its session is gone`() {
        val result = mock(MethodChannel.Result::class.java)

        ReleaseCameraCommand { null }.execute(call(), result)

        verify(result).success(true)
    }

    private fun call() = MethodCall("releaseCamera", mapOf("viewId" to VIEW_ID))

    private companion object {
        const val VIEW_ID = 42
    }
}
