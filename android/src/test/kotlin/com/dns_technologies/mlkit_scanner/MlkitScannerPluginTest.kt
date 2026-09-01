package com.dns_technologies.mlkit_scanner

import android.os.Handler
import androidx.lifecycle.Lifecycle
import com.dns_technologies.mlkit_scanner.models.ScannerSession
import com.dns_technologies.mlkit_scanner.scanner.models.Barcode
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.lifecycle.HiddenLifecycleReference
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.PluginRegistry
import io.flutter.plugin.platform.PlatformViewRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.invocation.InvocationOnMock
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify

internal class MlkitScannerPluginTest {
    @Test
    fun `start scan command updates shared session`() {
        val fixture = Fixture()

        fixture.plugin.onMethodCall(
            MethodCall(
                PluginConstants.startScanMethod,
                mapOf(
                    PluginConstants.viewIdArgument to VIEW_ID,
                    "type" to 0,
                    "delay" to 150,
                ),
            ),
            fixture.result,
        )

        verify(fixture.session).startScan(VIEW_ID, 150)
        verify(fixture.result).success(true)
    }

    @Test
    fun `camera and scan lifecycle commands address one platform view`() {
        val fixture = Fixture()

        fixture.plugin.onMethodCall(
            MethodCall(
                PluginConstants.pauseCameraMethod,
                mapOf(PluginConstants.viewIdArgument to VIEW_ID),
            ),
            fixture.result,
        )
        fixture.plugin.onMethodCall(
            MethodCall(
                PluginConstants.resumeCameraMethod,
                mapOf(PluginConstants.viewIdArgument to VIEW_ID),
            ),
            fixture.result,
        )
        fixture.plugin.onMethodCall(
            MethodCall(
                PluginConstants.cancelScanMethod,
                mapOf(PluginConstants.viewIdArgument to VIEW_ID),
            ),
            fixture.result,
        )

        verify(fixture.session).pauseCamera(VIEW_ID)
        verify(fixture.session).resumeCamera(VIEW_ID)
        verify(fixture.session).pauseScan(VIEW_ID)
        verify(fixture.result, times(3)).success(true)
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

    @Test
    fun `activity lifecycle is extracted through official Flutter adapter`() {
        val plugin = MlkitScannerPlugin(mock(Handler::class.java))
        val session = mock(ScannerSession::class.java)
        val binding = mock(ActivityPluginBinding::class.java)
        val lifecycle = mock(Lifecycle::class.java)
        plugin.setField("scannerSession", session)
        doAnswer { HiddenLifecycleReference(lifecycle) }.`when`(binding).lifecycle

        plugin.onAttachedToActivity(binding)

        verify(session).attachHostLifecycle(lifecycle)
    }

    @Test
    fun `final activity detach releases activity scoped scanner session`() {
        val plugin = MlkitScannerPlugin(mock(Handler::class.java))
        val session = mock(ScannerSession::class.java)
        plugin.setField("scannerSession", session)

        plugin.onDetachedFromActivity()

        verify(session).detachHostLifecycle()
        verify(session).release()
        assertEquals(null, plugin.getField<ScannerSession?>("scannerSession"))
    }

    @Test
    fun `configuration detach preserves scanner session`() {
        val plugin = MlkitScannerPlugin(mock(Handler::class.java))
        val session = mock(ScannerSession::class.java)
        plugin.setField("scannerSession", session)

        plugin.onDetachedFromActivityForConfigChanges()

        verify(session).detachHostLifecycle()
        verify(session, never()).release()
        assertSame(session, plugin.getField<ScannerSession?>("scannerSession"))
    }

    @Test
    fun `released session cannot remove its replacement`() {
        val plugin = MlkitScannerPlugin(mock(Handler::class.java))
        val releasedSession = mock(ScannerSession::class.java)
        val replacementSession = mock(ScannerSession::class.java)
        plugin.setField("scannerSession", replacementSession)

        plugin.javaClass.getDeclaredMethod(
            "removeScannerSession",
            ScannerSession::class.java,
        ).apply { isAccessible = true }.invoke(plugin, releasedSession)

        assertSame(replacementSession, plugin.getField<ScannerSession?>("scannerSession"))
    }

    @Test
    fun `command scope is recreated when plugin attaches to another engine`() {
        val plugin = MlkitScannerPlugin(mock(Handler::class.java))
        val binding = mock(FlutterPlugin.FlutterPluginBinding::class.java)
        doReturn(mock(BinaryMessenger::class.java)).`when`(binding).binaryMessenger
        doReturn(mock(PlatformViewRegistry::class.java)).`when`(binding).platformViewRegistry
        val detachedScope = plugin.getField<CoroutineScope>("commandScope")

        plugin.onDetachedFromEngine(binding)
        plugin.onAttachedToEngine(binding)

        val attachedScope = plugin.getField<CoroutineScope>("commandScope")
        assertFalse(detachedScope.isActive)
        assertNotSame(detachedScope, attachedScope)
        assertTrue(attachedScope.isActive)

        plugin.onDetachedFromEngine(binding)
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

        fun MlkitScannerPlugin.setField(name: String, value: Any) {
            javaClass.getDeclaredField(name).apply { isAccessible = true }.set(this, value)
        }

        @Suppress("UNCHECKED_CAST")
        fun <T> MlkitScannerPlugin.getField(name: String): T =
            javaClass.getDeclaredField(name).apply { isAccessible = true }.get(this) as T
    }
}
