package com.dns_technologies.mlkit_scanner

import com.dns_technologies.mlkit_scanner.scanner.Scanner
import com.dns_technologies.mlkit_scanner.scanner.ScannerView

import android.app.Activity
import android.os.Handler
import androidx.lifecycle.Lifecycle
import com.dns_technologies.mlkit_scanner.scanner.models.Barcode
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.embedding.engine.plugins.lifecycle.HiddenLifecycleReference
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.PluginRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.invocation.InvocationOnMock
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions

internal class ScannerPluginTransportTest {
    @Test
    fun `pause method releases current camera work`() {
        val fixture = Fixture()
        fixture.plugin.onMethodCall(MethodCall("pauseCameraMethod", null), fixture.result)

        verify(fixture.device).releaseCamera()
        verify(fixture.result).success(true)
    }

    @Test
    fun `point controls dispatch without a view address`() {
        for ((method, arguments) in listOf(
            "startScan" to mapOf("type" to 0, "delay" to 100),
            "cancelScan" to null,
            "setZoomRatio" to mapOf("value" to 2.0),
            "toggleFlash" to mapOf("value" to true),
            "setScanDelay" to mapOf("delay" to 200),
            "setCropAreaMethod" to mapOf("cropRect" to emptyMap<String, Any>()),
        )) {
            val fixture = Fixture()
            fixture.plugin.onMethodCall(MethodCall(method, arguments), fixture.result)
            verify(fixture.result).success(true)
            verify(fixture.result, never()).notImplemented()
        }
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
        val plugin = MlkitScannerPlugin(mock(Handler::class.java), mock(MethodChannel::class.java))
        val firstBinding = mock(ActivityPluginBinding::class.java)
        val secondBinding = mock(ActivityPluginBinding::class.java)
        val addedListeners = mutableListOf<PluginRegistry.RequestPermissionsResultListener>()
        val removedListeners = mutableListOf<PluginRegistry.RequestPermissionsResultListener>()
        val lifecycle = mock(Lifecycle::class.java)
        doReturn(Lifecycle.State.RESUMED).`when`(lifecycle).currentState
        listOf(firstBinding, secondBinding).forEach { binding ->
            doReturn(mock(Activity::class.java)).`when`(binding).activity
            doAnswer { HiddenLifecycleReference(lifecycle) }.`when`(binding).lifecycle
            doAnswer { invocation: InvocationOnMock ->
                addedListeners += invocation.getArgument<PluginRegistry.RequestPermissionsResultListener>(0)
                null
            }.`when`(binding).addRequestPermissionsResultListener(anyValue())
            doAnswer { invocation: InvocationOnMock ->
                removedListeners += invocation.getArgument<PluginRegistry.RequestPermissionsResultListener>(0)
                null
            }.`when`(binding).removeRequestPermissionsResultListener(anyValue())
        }

        plugin.attach(firstBinding)
        plugin.detach(isFinal = false)
        plugin.attach(secondBinding)
        plugin.detach(isFinal = true)

        assertEquals(2, addedListeners.size)
        assertEquals(2, removedListeners.size)
        assertSame(addedListeners.first(), removedListeners.first())
        assertSame(addedListeners.first(), addedListeners.last())
        assertSame(addedListeners.first(), removedListeners.last())
    }

    @Test
    fun `activity lifecycle is extracted through official Flutter adapter`() {
        val plugin = MlkitScannerPlugin(mock(Handler::class.java), mock(MethodChannel::class.java))
        val scanner = mock(Scanner::class.java)
        val binding = mock(ActivityPluginBinding::class.java)
        val lifecycle = mock(Lifecycle::class.java)
        doReturn(mock(Activity::class.java)).`when`(binding).activity
        plugin.setField("scanner", scanner)
        doAnswer { HiddenLifecycleReference(lifecycle) }.`when`(binding).lifecycle
        doReturn(Lifecycle.State.RESUMED).`when`(lifecycle).currentState

        plugin.attach(binding)

        inOrder(binding, scanner).apply {
            verify(binding).addRequestPermissionsResultListener(anyValue())
            verify(scanner).attachActivity(lifecycle)
        }
        verify(lifecycle, never()).addObserver(anyValue())
    }

    @Test
    fun `final activity detach releases activity scoped scanner hardware`() {
        val plugin = MlkitScannerPlugin(mock(Handler::class.java), mock(MethodChannel::class.java))
        val scanner = mock(Scanner::class.java)
        plugin.setField("scanner", scanner)

        plugin.detach(isFinal = true)

        verify(scanner).dispose()
        assertEquals(null, plugin.getField<Scanner?>("scanner"))
    }

    @Test
    fun `configuration detach preserves scanner hardware`() {
        val plugin = MlkitScannerPlugin(mock(Handler::class.java), mock(MethodChannel::class.java))
        val scanner = mock(Scanner::class.java)
        plugin.setField("scanner", scanner)

        plugin.detach(isFinal = false)

        verify(scanner, never()).dispose()
        assertSame(scanner, plugin.getField<Scanner?>("scanner"))
    }

    @Test
    fun `engine disposal attempts all cleanup after camera detachment and permission listener failures`() {
        val fixture = Fixture()
        val activity = mock(ActivityPluginBinding::class.java)
        val lifecycle = mock(Lifecycle::class.java)
        val scanner = mock(Scanner::class.java)
        doReturn(mock(Activity::class.java)).`when`(activity).activity
        doAnswer { HiddenLifecycleReference(lifecycle) }.`when`(activity).lifecycle
        doReturn(Lifecycle.State.RESUMED).`when`(lifecycle).currentState
        fixture.plugin.setField("scanner", scanner)
        fixture.plugin.attach(activity)
        val detachFailure = IllegalStateException("camera detach")
        val listenerFailure = IllegalStateException("permission listener detach")
        doThrow(detachFailure).`when`(scanner).detachActivity()
        doThrow(listenerFailure).`when`(activity).removeRequestPermissionsResultListener(anyValue())

        assertSame(detachFailure, runCatching { fixture.plugin.dispose() }.exceptionOrNull())

        assertEquals(listOf(listenerFailure), detachFailure.suppressed.toList())
        assertTrue(fixture.plugin.isDisposed)
        assertEquals(null, fixture.plugin.getField<ActivityPluginBinding?>("activityBinding"))
        val permissions = fixture.plugin.getField<com.dns_technologies.mlkit_scanner.permissions.PermissionGateway>("permissionGateway")
        assertEquals(null, permissions.getField<Activity?>("activity"))
        assertFalse(fixture.plugin.getField<CoroutineScope>("commandScope").isActive)
        verify(scanner).detachActivity()
        verify(scanner).dispose()
        verify(fixture.channel).setMethodCallHandler(null)
    }

    @Test
    fun `engine detach reports cancellation through the original result only once`() {
        val fixture = Fixture()
        val recording = com.dns_technologies.mlkit_scanner.commands.base.RecordingScanner().apply {
            zoomCompletion = CompletableDeferred()
        }
        doReturn(VIEW_ID).`when`(recording.device).viewId
        doAnswer { recording.releaseCamera(); null }.`when`(recording.device).detachActivity()
        fixture.plugin.setField("scanner", recording.device)
        fixture.plugin.onMethodCall(
            MethodCall(PluginConstants.setZoomRatioMethod, mapOf("value" to 2.5)),
            fixture.result,
        )

        verifyNoInteractions(fixture.result)
        fixture.plugin.dispose()
        recording.zoomCompletion?.complete(Unit)

        verify(fixture.result).error(
            PluginError.CameraSessionDisposed.errorCode,
            PluginError.CameraSessionDisposed.message,
            null,
        )
        verifyNoMoreInteractions(fixture.result)
    }

    private class Fixture {
        val channel: MethodChannel = mock(MethodChannel::class.java)
        val plugin = MlkitScannerPlugin(mock(Handler::class.java), channel,
            commandScope = CoroutineScope(Dispatchers.Unconfined))
        val device: Scanner = mock(Scanner::class.java)
        val result: MethodChannel.Result = mock(MethodChannel.Result::class.java)

        val view: ScannerView = mock(ScannerView::class.java)

        init {
            doReturn(VIEW_ID).`when`(view).viewId
            doReturn(VIEW_ID).`when`(device).viewId
            plugin.setField("scanner", device)
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

        fun Any.setField(name: String, value: Any) {
            javaClass.getDeclaredField(name).apply { isAccessible = true }.set(this, value)
            if (value is Scanner) {
                doAnswer { invocation ->
                    javaClass.getDeclaredMethod("scannerReleased", Scanner::class.java, Throwable::class.java)
                        .apply { isAccessible = true }.invoke(this, value, invocation.getArgument<Throwable>(0))
                    null
                }.`when`(value).dispose(anyValue())
            }
        }

        @Suppress("UNCHECKED_CAST")
        fun <T> Any.getField(name: String): T =
            javaClass.getDeclaredField(name).apply { isAccessible = true }.get(this) as T

    }
}
