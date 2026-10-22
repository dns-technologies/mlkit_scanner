package com.dns_technologies.mlkit_scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertFalse
import org.junit.Test

internal class PluginErrorTest {
    @Test
    fun `stable errors expose their documented channel codes and messages`() {
        val errors = listOf(
            PluginError.InitCameraError to "1",
            PluginError.AuthorizationCameraError to "2",
            PluginError.CameraIsNotInitialized to "3",
            PluginError.DeviceHasNotFlash to "4",
            PluginError.InvalidArguments to "5",
            PluginError.DeviceHasNotZoom to "6",
            PluginError.UnknownError to "7",
            PluginError.CameraSessionDisposed to "8",
        )

        errors.forEach { (error, code) ->
            assertEquals(code, error.errorCode)
            assertNotNull(error.message)
        }
    }

    @Test
    fun `camera error preserves its description and native cause without an operation tag`() {
        val cause = IllegalStateException("camera disconnected")
        val error = PluginError.CameraError(
            message = "Camera failed to open",
            viewId = 4,
            cause = cause,
            cameraStateErrorCode = 3,
        )
        val details = error.details as Map<*, *>

        assertEquals("9", error.errorCode)
        assertEquals("Camera failed to open", error.message)
        assertFalse(details.containsKey("operation"))
        assertEquals(setOf("stacktrace"), details.keys)
        assertEquals(cause.stackTraceToString(), details["stacktrace"])
        assertSame(cause, error.cause)
    }

    @Test
    fun `contextualize adds the view without replacing the description or native failure`() {
        val cause = IllegalArgumentException("out of range")
        val original = PluginError.CameraError(
            message = "Failed to set camera zoom ratio to 2.0",
            cause = cause,
            cameraStateErrorCode = 5,
        )

        val contextualized = original.contextualize(12)

        assertEquals(original.message, contextualized.message)
        assertEquals(12, contextualized.viewId)
        assertEquals(5, contextualized.cameraStateErrorCode)
        assertSame(cause, contextualized.cause)
    }

    @Test
    fun `contextualize preserves the source stack without an SDK cause`() {
        val original = PluginError.CameraError("Camera failed to open")

        val contextualized = original.contextualize(12)

        assertEquals(original.details, contextualized.details)
    }

    @Test
    fun `channel message avoids repeating an identical SDK description`() {
        val error = PluginError.CameraError("Camera closed", cause = IllegalStateException("Camera closed"))

        assertEquals("Camera closed", error.channelMessage)
    }
}
