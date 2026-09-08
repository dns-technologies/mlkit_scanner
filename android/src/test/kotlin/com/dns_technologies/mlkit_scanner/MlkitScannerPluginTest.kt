package com.dns_technologies.mlkit_scanner

import android.content.Context
import android.os.Handler
import com.dns_technologies.mlkit_scanner.scanner.Scanner
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.platform.PlatformViewFactory
import io.flutter.plugin.platform.PlatformViewRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import org.junit.Assert.*
import org.junit.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.*

internal class MlkitScannerPluginTest {
    @Test
    fun `pause method delegates to the scanner without view arguments`() {
        val scanner = mock(Scanner::class.java)
        val plugin = releasePlugin(scanner)
        val result = mock(MethodChannel.Result::class.java)

        plugin.onMethodCall(MethodCall(PluginConstants.pauseCameraMethod, null), result)

        verify(scanner).releaseCamera()
        verify(result).success(true)
        verifyNoMoreInteractions(result)
        plugin.dispose()
    }

    @Test
    fun `pause method succeeds repeatedly without an allocated scanner`() {
        val plugin = releasePlugin(null)
        val result = mock(MethodChannel.Result::class.java)

        repeat(2) { plugin.onMethodCall(MethodCall(PluginConstants.pauseCameraMethod, null), result) }

        verify(result, times(2)).success(true)
        verifyNoMoreInteractions(result)
        plugin.dispose()
    }

    @Test
    fun `pause method reports native cancellation failure`() {
        val scanner = mock(Scanner::class.java)
        val plugin = releasePlugin(scanner)
        val result = mock(MethodChannel.Result::class.java)
        doAnswer { throw PluginError.CameraSessionDisposed }.`when`(scanner).releaseCamera()

        plugin.onMethodCall(MethodCall(PluginConstants.pauseCameraMethod, null), result)

        verify(result).error(PluginError.CameraSessionDisposed.errorCode, PluginError.CameraSessionDisposed.message, null)
        verifyNoMoreInteractions(result)
        plugin.dispose()
    }

    @Test
    fun `each engine attachment gets a fresh command scope`() {
        val plugin = MlkitScannerPlugin(mock(Handler::class.java))
        val engine = engine()
        plugin.onAttachedToEngine(engine)
        val firstScope = plugin.scope()
        plugin.onDetachedFromEngine(engine)
        assertTrue(plugin.isDisposed)
        assertFalse(firstScope.isActive)

        plugin.onAttachedToEngine(engine)

        assertNotSame(firstScope, plugin.scope())
        assertTrue(plugin.scope().isActive)
        plugin.onDetachedFromEngine(engine)
    }

    @Test
    fun `removed capture and release channel methods are not dispatched`() {
        val scanner = mock(Scanner::class.java)
        val plugin = releasePlugin(scanner)
        val result = mock(MethodChannel.Result::class.java)

        for (method in listOf("captureCamera", "releaseCamera")) {
            plugin.onMethodCall(MethodCall(method, null), result)
        }

        verify(result, times(2)).notImplemented()
        verifyNoMoreInteractions(result)
        verifyNoInteractions(scanner)
        plugin.dispose()
    }

    @Test
    fun `stale factory cannot create a view after engine reattachment`() {
        val plugin = MlkitScannerPlugin(mock(Handler::class.java))
        val firstEngine = engine()
        var factory: PlatformViewFactory? = null
        val registry = firstEngine.platformViewRegistry
        doAnswer { factory = it.getArgument(1); true }.`when`(registry)
            .registerViewFactory(anyString(), anyValue())
        plugin.onAttachedToEngine(firstEngine)
        val oldFactory = checkNotNull(factory)
        plugin.onDetachedFromEngine(firstEngine)
        val secondEngine = engine()
        plugin.onAttachedToEngine(secondEngine)

        val error = runCatching { oldFactory.create(mock(Context::class.java), 42, mapOf("viewId" to 42)) }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertFalse(plugin.isDisposed)
        plugin.onDetachedFromEngine(secondEngine)
    }

    @Test
    fun `failed factory registration releases attachment resources`() {
        val plugin = MlkitScannerPlugin(mock(Handler::class.java))
        val engine = engine()
        val registry = engine.platformViewRegistry
        doReturn(false).`when`(registry).registerViewFactory(anyString(), anyValue())

        assertTrue(runCatching { plugin.onAttachedToEngine(engine) }.exceptionOrNull() is IllegalStateException)

        assertTrue(plugin.isDisposed)
        assertFalse(plugin.scope().isActive)
    }

    @Test
    fun `duplicate engine attach cannot replace a live attachment`() {
        val plugin = MlkitScannerPlugin(mock(Handler::class.java))
        val engine = engine()
        plugin.onAttachedToEngine(engine)
        val scope = plugin.scope()

        assertTrue(runCatching { plugin.onAttachedToEngine(engine()) }.exceptionOrNull() is IllegalStateException)

        assertSame(scope, plugin.scope())
        assertFalse(plugin.isDisposed)
        plugin.onDetachedFromEngine(engine)
    }

    private fun releasePlugin(scanner: Scanner?): MlkitScannerPlugin =
        MlkitScannerPlugin(mock(Handler::class.java), mock(MethodChannel::class.java)).also { plugin ->
            plugin.javaClass.getDeclaredField("scanner").apply { isAccessible = true }.set(plugin, scanner)
        }

    private fun engine(): FlutterPlugin.FlutterPluginBinding = mock(FlutterPlugin.FlutterPluginBinding::class.java).also {
        doReturn(mock(BinaryMessenger::class.java)).`when`(it).binaryMessenger
        val registry = mock(PlatformViewRegistry::class.java)
        doReturn(true).`when`(registry).registerViewFactory(anyString(), anyValue())
        doReturn(registry).`when`(it).platformViewRegistry
    }

    private fun MlkitScannerPlugin.scope(): CoroutineScope =
        javaClass.getDeclaredField("commandScope").apply { isAccessible = true }.get(this) as CoroutineScope

    private companion object { fun <T> anyValue(): T = org.mockito.ArgumentMatchers.any<T>() }
}
