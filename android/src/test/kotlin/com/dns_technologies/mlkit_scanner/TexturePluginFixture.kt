package com.dns_technologies.mlkit_scanner

import android.os.Looper
import com.dns_technologies.mlkit_scanner.scanner.Scanner
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import org.mockito.Mockito.*

internal class TexturePluginFixture {
    val plugin = MlkitScannerPlugin()
    val scanner = mock(Scanner::class.java)
    val channel = MethodChannel(mock(BinaryMessenger::class.java), "mlkit_channel")
    init {
        doReturn(kotlinx.coroutines.CompletableDeferred(Unit)).`when`(scanner).disposal
        set("channel", channel); set("scanner", scanner)
    }
    fun set(name: String, value: Any?) { plugin.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(plugin, value) }
    fun call(method: String, arguments: Any? = null): Reply {
        val reply = Reply(); plugin.onMethodCall(MethodCall(method, arguments), reply); return reply
    }
    fun open(viewId: Int): String {
        call("registerScanner", mapOf("viewId" to viewId))
        return call("openCapture", mapOf("viewId" to viewId)).value as String
    }
    class Reply : MethodChannel.Result {
        var value: Any? = null
        var error: String? = null
        var replies = 0
        override fun success(result: Any?) { value = result; replies++ }
        override fun error(code: String, message: String?, details: Any?) { error = code; replies++ }
        override fun notImplemented() { error = "notImplemented"; replies++ }
    }
}
