package com.dns_technologies.mlkit_scanner

import android.os.Handler
import com.dns_technologies.mlkit_scanner.models.ScannerSession
import com.dns_technologies.mlkit_scanner.scanner.models.Barcode
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.PluginRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.mockito.invocation.InvocationOnMock
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify

internal class MlkitScannerPluginTest {
    @Test
    fun `start scan command updates shared session`() {
        val fixture = Fixture()

        fixture.plugin.onMethodCall(
            MethodCall(
                PluginConstants.startScanMethod,
                mapOf(
                    "delay" to 150,
                ),
            ),
            fixture.result,
        )

        verify(fixture.session).startScan(150)
        verify(fixture.result).success(true)
    }

    @Test
    fun `dispose command removes only referenced platform view`() {
        val fixture = Fixture()

        fixture.plugin.onMethodCall(
            MethodCall(
                PluginConstants.disposeCameraMethod,
                mapOf(PluginConstants.viewIdArgument to VIEW_ID),
            ),
            fixture.result,
        )

        verify(fixture.session).disposeView(VIEW_ID)
        verify(fixture.result).success(true)
    }

    @Test
    fun `dispose command rejects legacy payload without view identity`() {
        val fixture = Fixture()

        fixture.plugin.onMethodCall(
            MethodCall(PluginConstants.disposeCameraMethod, null),
            fixture.result,
        )

        verify(fixture.session, never()).disposeView(VIEW_ID)
        verify(fixture.result).error(
            PluginError.InvalidArguments.errorCode,
            PluginError.InvalidArguments.message,
            null,
        )
    }

    @Test
    fun `scan result event contains addressed barcode payload`() {
        val fixture = Fixture()

        fixture.plugin.javaClass.getDeclaredMethod(
            "emitScanResult",
            Int::class.javaPrimitiveType,
            Barcode::class.java,
        ).apply { isAccessible = true }.invoke(fixture.plugin, VIEW_ID, BARCODE)

        verify(fixture.channel).invokeMethod(
            PluginConstants.scanResultMethod,
            mapOf(
                PluginConstants.viewIdArgument to VIEW_ID,
                PluginConstants.barcodeArgument to BARCODE.toMap(),
            ),
        )
    }

    @Test
    fun `permission listener identity survives activity recreation`() {
        val plugin = MlkitScannerPlugin(mock(Handler::class.java))
        val firstBinding = mock(ActivityPluginBinding::class.java)
        val secondBinding = mock(ActivityPluginBinding::class.java)
        val addedListeners = mutableListOf<PluginRegistry.RequestPermissionsResultListener>()
        val removedListeners = mutableListOf<PluginRegistry.RequestPermissionsResultListener>()
        listOf(firstBinding, secondBinding).forEach { binding ->
            doAnswer { invocation: InvocationOnMock ->
                addedListeners += invocation.getArgument<PluginRegistry.RequestPermissionsResultListener>(0)
                null
            }.`when`(binding).addRequestPermissionsResultListener(anyValue())
            doAnswer { invocation: InvocationOnMock ->
                removedListeners += invocation.getArgument<PluginRegistry.RequestPermissionsResultListener>(0)
                null
            }.`when`(binding).removeRequestPermissionsResultListener(anyValue())
        }

        plugin.onAttachedToActivity(firstBinding)
        plugin.onDetachedFromActivityForConfigChanges()
        plugin.onReattachedToActivityForConfigChanges(secondBinding)
        plugin.onDetachedFromActivity()

        assertEquals(2, addedListeners.size)
        assertEquals(2, removedListeners.size)
        assertSame(addedListeners.first(), removedListeners.first())
        assertSame(addedListeners.first(), addedListeners.last())
        assertSame(addedListeners.first(), removedListeners.last())
    }

    private class Fixture {
        val plugin = MlkitScannerPlugin(mock(Handler::class.java))
        val session: ScannerSession = mock(ScannerSession::class.java)
        val channel: MethodChannel = mock(MethodChannel::class.java)
        val result: MethodChannel.Result = mock(MethodChannel.Result::class.java)

        init {
            plugin.setField("scannerSession", session)
            plugin.setField("channel", channel)
        }

        private fun MlkitScannerPlugin.setField(name: String, value: Any) {
            javaClass.getDeclaredField(name).apply { isAccessible = true }.set(this, value)
        }
    }

    private companion object {
        const val VIEW_ID = 42
        val BARCODE = Barcode(
            rawValue = "1234567890",
            displayValue = "1234567890",
            format = 1,
            valueType = 1,
        )

        fun <T> anyValue(): T = org.mockito.ArgumentMatchers.any<T>()
    }
}
