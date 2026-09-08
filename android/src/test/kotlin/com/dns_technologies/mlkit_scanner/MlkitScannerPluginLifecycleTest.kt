package com.dns_technologies.mlkit_scanner

import io.flutter.plugin.common.MethodChannel

import android.content.Context
import android.os.Handler
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.dns_technologies.mlkit_scanner.PluginError
import com.dns_technologies.mlkit_scanner.scanner.Scanner
import com.dns_technologies.mlkit_scanner.scanner.ScannerView
import com.dns_technologies.mlkit_scanner.scanner.ScannerViewFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
internal class MlkitScannerPluginLifecycleTest {
    @Test
    fun `plugin forwards actual Activity lifecycle without installing an observer`() {
        val host = TestHostLifecycleOwner(Lifecycle.State.RESUMED)
        val scanner = mockDevice()
        val plugin = plugin().apply { setDevice(scanner) }
        plugin.attach(activity(host.lifecycle))

        verify(scanner).attachActivity(host.lifecycle)
        assertEquals(0, host.observerCount)
        clearInvocations(scanner)
        host.moveTo(Lifecycle.State.STARTED)
        host.moveTo(Lifecycle.State.CREATED)
        host.moveTo(Lifecycle.State.RESUMED)
        org.mockito.Mockito.verifyNoInteractions(scanner)
        plugin.dispose()
    }

    @Test
    fun `configuration reattachment supplies replacement lifecycle without capturing`() {
        val first = TestHostLifecycleOwner(Lifecycle.State.RESUMED)
        val next = TestHostLifecycleOwner(Lifecycle.State.CREATED)
        val scanner = mockDevice()
        val plugin = plugin().apply { setDevice(scanner) }
        plugin.onAttachedToActivity(activity(first.lifecycle))
        plugin.onDetachedFromActivityForConfigChanges()
        plugin.onReattachedToActivityForConfigChanges(activity(next.lifecycle))

        verify(scanner).detachActivity()
        verify(scanner).attachActivity(first.lifecycle)
        verify(scanner).attachActivity(next.lifecycle)
        verify(scanner, never()).dispose()
        verify(scanner, never()).select(anyValue())
        plugin.dispose()
    }

    @Test
    fun `registration allocates UI only and disposal unregisters its exact view`() {
        var allocations = 0
        val plugin = MlkitScannerPlugin(mock(Handler::class.java), mock(MethodChannel::class.java),
            scannerFactory = { _, _, _ -> allocations++; mockDevice() })
        val view = plugin.createView(RuntimeEnvironment.getApplication(), VIEW_ID, mapOf("viewId" to VIEW_ID))
        assertEquals(0, allocations)
        assertNull(plugin.currentScanner())
        assertSame(view, plugin.views[VIEW_ID])
        view.dispose()
        assertNull(plugin.views[VIEW_ID])
        // Reusing an unregistered address is valid; an old second disposal cannot remove it.
        val replacement = plugin.createView(RuntimeEnvironment.getApplication(), VIEW_ID, mapOf("viewId" to VIEW_ID))
        view.dispose()
        assertEquals(VIEW_ID, replacement.viewId)
        assertSame(replacement, plugin.views[VIEW_ID])
        plugin.dispose()
        org.junit.Assert.assertTrue(plugin.views.isEmpty())
    }

    @Test
    fun `controls resolve the single scanner independently of preview lifetime`() {
        val plugin = plugin()
        val first = plugin.createView(RuntimeEnvironment.getApplication(), VIEW_ID, mapOf("viewId" to VIEW_ID))
        plugin.createView(RuntimeEnvironment.getApplication(), VIEW_ID + 1, mapOf("viewId" to VIEW_ID + 1))
        val scanner = mockDevice()
        doReturn(VIEW_ID).`when`(scanner).viewId
        plugin.setDevice(scanner)
        assertSame(scanner, plugin.currentScanner())
        doAnswer { doReturn(null).`when`(scanner).viewId; null }.`when`(scanner).releaseCamera()
        first.dispose()
        assertSame(scanner, plugin.currentScanner())
        verify(scanner).releaseCamera()
    }

    @Test
    fun `pipeline expiry preserves views for a fresh capture`() {
        val plugin = plugin()
        val view = plugin.createView(RuntimeEnvironment.getApplication(), VIEW_ID, mapOf("viewId" to VIEW_ID))
        val scanner = mockDevice()
        plugin.setDevice(scanner)
        scanner.dispose()
        assertNull(plugin.scanner)
        view.dispose()
        plugin.createView(RuntimeEnvironment.getApplication(), VIEW_ID, mapOf("viewId" to VIEW_ID))
    }

    @Test
    fun `capture reuses the Flutter view after scanner expiry`() {
        var allocations = 0
        val devices = mutableListOf<Scanner>()
        val plugin = MlkitScannerPlugin(mock(Handler::class.java), mock(MethodChannel::class.java),
            scannerFactory = { _, released, _ ->
                allocations++
                mockDevice().also { scanner ->
                    scanner.releasing(released)
                    devices += scanner
                }
            })
        plugin.attach(activity(TestHostLifecycleOwner(Lifecycle.State.RESUMED).lifecycle))
        val registered = plugin.createView(RuntimeEnvironment.getApplication(), VIEW_ID, null)
        val first = checkNotNull(plugin.capture())
        assertSame(devices[0], first)
        verify(first).select(registered)
        first.dispose()
        assertNull(plugin.scanner)

        val next = plugin.capture()

        assertSame(devices[1], next)
        assertEquals(2, allocations)
        org.junit.Assert.assertFalse(registered.isDisposed)
        plugin.dispose()
        registered.dispose()
    }

    @Test
    fun `capture without Activity does not allocate scanner`() {
        var allocations = 0
        val plugin = MlkitScannerPlugin(mock(Handler::class.java), mock(MethodChannel::class.java),
            scannerFactory = { _, _, _ -> allocations++; mockDevice() })
        plugin.createView(RuntimeEnvironment.getApplication(), VIEW_ID, null)

        val scanner = plugin.capture()

        assertNull(scanner)
        assertEquals(0, allocations)
        plugin.dispose()
    }

    @Test
    fun `late old view disposal cannot release a replacement with the same address`() {
        val plugin = plugin()
        val old = plugin.createView(RuntimeEnvironment.getApplication(), VIEW_ID, null)
        val registered = plugin.createView(RuntimeEnvironment.getApplication(), VIEW_ID, null)
        val scanner = mockDevice()
        doReturn(VIEW_ID).`when`(scanner).viewId
        plugin.setDevice(scanner)

        old.dispose()

        verify(scanner, never()).releaseCamera()
        assertSame(registered, plugin.views[VIEW_ID])
        registered.dispose()
        assertNull(plugin.views[VIEW_ID])
        verify(scanner).releaseCamera()
        plugin.dispose()
    }

    @Test
    fun `capture of a missing or disposed view does not allocate a scanner`() {
        var allocations = 0
        val plugin = MlkitScannerPlugin(mock(Handler::class.java), mock(MethodChannel::class.java),
            scannerFactory = { _, _, _ -> allocations++; mockDevice() })
        plugin.attach(activity(TestHostLifecycleOwner(Lifecycle.State.RESUMED).lifecycle))
        assertNull(plugin.capture())
        val view = plugin.createView(RuntimeEnvironment.getApplication(), VIEW_ID, null)
        view.dispose()

        assertNull(plugin.capture())
        assertEquals(0, allocations)
        plugin.dispose()
    }

    @Test
    fun `engine reattachment drops old view references and ignores their disposal`() {
        val plugin = plugin()
        val old = plugin.createView(RuntimeEnvironment.getApplication(), VIEW_ID, null)
        plugin.dispose()
        org.junit.Assert.assertTrue(plugin.views.isEmpty())
        val binding = mock(io.flutter.embedding.engine.plugins.FlutterPlugin.FlutterPluginBinding::class.java)
        val registry = mock(io.flutter.plugin.platform.PlatformViewRegistry::class.java)
        doReturn(mock(io.flutter.plugin.common.BinaryMessenger::class.java)).`when`(binding).binaryMessenger
        doReturn(registry).`when`(binding).platformViewRegistry
        doReturn(true).`when`(registry).registerViewFactory(org.mockito.ArgumentMatchers.anyString(), anyValue())
        plugin.onAttachedToEngine(binding)
        val replacement = plugin.createView(RuntimeEnvironment.getApplication(), VIEW_ID, null)
        val scanner = mockDevice()
        doReturn(VIEW_ID).`when`(scanner).viewId
        plugin.setDevice(scanner)

        old.dispose()

        assertSame(replacement, plugin.views[VIEW_ID])
        verify(scanner, never()).releaseCamera()
        replacement.dispose()
        verify(scanner).releaseCamera()
        plugin.dispose()
    }

    @Test
    fun `repeated capture reuses scanner and attaches its Activity only once`() {
        var allocations = 0
        val scanner = mockDevice()
        val plugin = MlkitScannerPlugin(mock(Handler::class.java), mock(MethodChannel::class.java),
            scannerFactory = { _, _, _ -> allocations++; scanner })
        val lifecycle = TestHostLifecycleOwner(Lifecycle.State.RESUMED).lifecycle
        plugin.attach(activity(lifecycle))
        val view = plugin.createView(RuntimeEnvironment.getApplication(), VIEW_ID, null)

        assertSame(scanner, plugin.capture())
        assertSame(scanner, plugin.capture())

        assertEquals(1, allocations)
        verify(scanner).attachActivity(lifecycle)
        verify(scanner, times(2)).select(view)
        plugin.dispose()
    }

    @Test
    fun `failed selection disposes the scanner and allows a fresh capture`() {
        val first = mockDevice()
        val replacement = mockDevice()
        val devices = ArrayDeque(listOf(first, replacement))
        val plugin = MlkitScannerPlugin(mock(Handler::class.java), mock(MethodChannel::class.java),
            scannerFactory = { _, _, _ -> devices.removeFirst() })
        plugin.attach(activity(TestHostLifecycleOwner(Lifecycle.State.RESUMED).lifecycle))
        val view = plugin.createView(RuntimeEnvironment.getApplication(), VIEW_ID, null)
        val failure = IllegalStateException("select failed")
        doThrow(failure).`when`(first).select(view)
        val result = mock(MethodChannel.Result::class.java)

        plugin.onMethodCall(captureCall(), result)

        assertNull(plugin.scanner)
        verify(first).dispose(failure)
        verify(result).error(PluginError.UnknownError.errorCode, PluginError.UnknownError.message,
            mapOf("message" to failure.message, "stackTrace" to failure.stackTraceToString()))
        assertSame(replacement, plugin.capture())
        verify(replacement).select(view)
        plugin.dispose()
    }

    @Test
    fun `failed Activity attachment retains its error when scanner cleanup also fails`() {
        val scanner = mockDevice()
        val plugin = MlkitScannerPlugin(mock(Handler::class.java), mock(MethodChannel::class.java),
            scannerFactory = { _, _, _ -> scanner })
        val lifecycle = TestHostLifecycleOwner(Lifecycle.State.RESUMED).lifecycle
        plugin.attach(activity(lifecycle))
        plugin.createView(RuntimeEnvironment.getApplication(), VIEW_ID, null)
        val failure = IllegalStateException("attach failed")
        val cleanup = IllegalStateException("dispose failed")
        doThrow(failure).`when`(scanner).attachActivity(lifecycle)
        doThrow(cleanup).`when`(scanner).dispose(failure)
        val result = mock(MethodChannel.Result::class.java)

        plugin.onMethodCall(captureCall(), result)

        assertNull(plugin.scanner)
        verify(scanner).dispose(failure)
        verify(scanner, never()).select(anyValue())
        assertEquals(listOf(cleanup), failure.suppressed.toList())
        verify(result).error(PluginError.UnknownError.errorCode, PluginError.UnknownError.message,
            mapOf("message" to failure.message, "stackTrace" to failure.stackTraceToString()))
        plugin.dispose()
    }

    private fun captureCall() = io.flutter.plugin.common.MethodCall("captureCamera", mapOf(
        "viewId" to VIEW_ID,
        "configuration" to mapOf("zoomRatio" to 1.0, "torchEnabled" to false,
            "cropRect" to null, "scanEnabled" to false, "scanDelay" to 0),
    ))

    private fun MlkitScannerPlugin.capture(): Scanner? {
        val result = mock(MethodChannel.Result::class.java)
        onMethodCall(captureCall(), result)
        verify(result).success(true)
        return scanner
    }

    private fun plugin() = MlkitScannerPlugin(mainHandler = mock(Handler::class.java), channel = mock(MethodChannel::class.java))

    private fun MlkitScannerPlugin.setDevice(scanner: Scanner) {
        javaClass.getDeclaredField("scanner").apply { isAccessible = true }.set(this, scanner)
        scanner.releasing { device, cause -> invokePrivate("scannerReleased", arrayOf(Scanner::class.java, Throwable::class.java), device, cause) }
    }

    /** Runtime doubles retain the actual terminal callback supplied by their owner. */
    private fun Scanner.releasing(callback: (Scanner, Throwable) -> Unit): Scanner = apply {
        doAnswer { invocation ->
            callback(this, invocation.getArgument(0))
            null
        }.`when`(this).dispose(anyValue())
    }

    /** Uses real platform-view construction with an isolated hardware double. */
    private fun mockDevice(): Scanner = mock(Scanner::class.java)

    private val MlkitScannerPlugin.scanner: Scanner?
        get() = javaClass.getDeclaredField("scanner").apply { isAccessible = true }.get(this) as Scanner?

    @Suppress("UNCHECKED_CAST")
    private val MlkitScannerPlugin.views: Map<Int, ScannerView>
        get() = javaClass.getDeclaredField("views").apply { isAccessible = true }.get(this) as Map<Int, ScannerView>

    private fun MlkitScannerPlugin.createView(context: Context, platformViewId: Int, creationParams: Any?): ScannerView =
        invokePrivate("createView", arrayOf(Context::class.java, Int::class.javaPrimitiveType!!), context, platformViewId) as ScannerView

    private fun activity(lifecycle: Lifecycle): io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding =
        mock(io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding::class.java).also { binding ->
            doReturn(mock(android.app.Activity::class.java)).`when`(binding).activity
            doReturn(io.flutter.embedding.engine.plugins.lifecycle.HiddenLifecycleReference(lifecycle)).`when`(binding).lifecycle
        }

    /** Exercises private bridge seams without widening the production API solely for tests. */
    private fun MlkitScannerPlugin.invokePrivate(name: String, types: Array<Class<*>> = emptyArray(), vararg args: Any): Any? =
        try {
            javaClass.getDeclaredMethod(name, *types).apply { isAccessible = true }.invoke(this, *args)
        } catch (error: java.lang.reflect.InvocationTargetException) {
            throw error.targetException
        }

    private class TestHostLifecycleOwner(
        initialState: Lifecycle.State,
    ) : LifecycleOwner {
        private val registry = LifecycleRegistry.createUnsafe(this).apply {
            currentState = initialState
        }

        override val lifecycle: Lifecycle
            get() = registry

        val observerCount: Int
            get() = registry.observerCount

        fun moveTo(state: Lifecycle.State) {
            registry.currentState = state
        }
    }

    private companion object {
        const val VIEW_ID = 42
        fun <T> anyValue(): T = any<T>()
    }
}
