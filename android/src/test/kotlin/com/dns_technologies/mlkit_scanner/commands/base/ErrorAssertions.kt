package com.dns_technologies.mlkit_scanner.commands.base

import com.dns_technologies.mlkit_scanner.PluginError
import io.flutter.plugin.common.MethodChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.eq
import org.mockito.Mockito.verify

/** Checks the native error contract without depending on exact runtime stack frames. */
internal fun verifyError(result: MethodChannel.Result, error: PluginError): String {
    val details = ArgumentCaptor.forClass(Map::class.java)
    verify(result).error(eq(error.errorCode), eq(error.channelMessage), details.capture())
    assertEquals(setOf("stacktrace"), details.value.keys)
    val stack = details.value["stacktrace"] as String
    assertTrue(stack.isNotBlank())
    return stack
}
