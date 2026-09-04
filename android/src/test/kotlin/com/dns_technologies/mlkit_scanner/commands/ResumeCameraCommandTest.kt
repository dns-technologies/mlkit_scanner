package com.dns_technologies.mlkit_scanner.commands

import com.dns_technologies.mlkit_scanner.commands.base.RecordingScannerSession
import com.dns_technologies.mlkit_scanner.commands.base.SessionCall
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify

internal class ResumeCameraCommandTest {
    @Test
    fun `resume delegates to the addressed view`() {
        val session = RecordingScannerSession()
        val result = mock(MethodChannel.Result::class.java)

        ResumeCameraCommand { session }.execute(call(), result)

        assertEquals(listOf(SessionCall("resumeCamera", VIEW_ID)), session.calls)
        verify(result).success(true)
    }

    @Test
    fun `resume completes after its session is gone`() {
        val result = mock(MethodChannel.Result::class.java)

        ResumeCameraCommand { null }.execute(call(), result)

        verify(result).success(true)
    }

    private fun call() = MethodCall("resumeCamera", mapOf("viewId" to VIEW_ID))

    private companion object {
        const val VIEW_ID = 42
    }
}
