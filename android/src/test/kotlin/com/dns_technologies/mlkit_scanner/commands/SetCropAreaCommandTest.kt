package com.dns_technologies.mlkit_scanner.commands

import com.dns_technologies.mlkit_scanner.commands.base.RecordingScannerSession
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify

internal class SetCropAreaCommandTest {
    @Test
    fun `crop updates normalized geometry for the addressed view`() {
        val session = RecordingScannerSession()
        val result = mock(MethodChannel.Result::class.java)

        SetCropAreaCommand { session }.execute(call(), result)

        val invocation = session.calls.single()
        assertEquals("setCropArea", invocation.name)
        assertEquals(VIEW_ID, invocation.viewId)
        val crop = invocation.value as com.dns_technologies.mlkit_scanner.scanner.models.RecognizeVisorCropRect
        assertEquals(0.5, crop.scaleWidth, 0.0)
        assertEquals(0.75, crop.scaleHeight, 0.0)
        assertEquals(0.1, crop.centerOffsetX, 0.0)
        assertEquals(-0.2, crop.centerOffsetY, 0.0)
        verify(result).success(true)
    }

    @Test
    fun `crop completes after its session is gone`() {
        val result = mock(MethodChannel.Result::class.java)

        SetCropAreaCommand { null }.execute(call(), result)

        verify(result).success(true)
    }

    private fun call() = MethodCall(
        "setCropArea",
        mapOf(
            "viewId" to VIEW_ID,
            "cropRect" to mapOf(
                "scaleWidth" to 0.5,
                "scaleHeight" to 0.75,
                "offsetX" to 0.1,
                "offsetY" to -0.2,
            ),
        ),
    )

    private companion object {
        const val VIEW_ID = 42
    }
}
