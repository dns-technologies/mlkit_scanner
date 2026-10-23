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
    private var nextCapture = 0
    val channel = MethodChannel(mock(BinaryMessenger::class.java), "mlkit_channel")
    init {
        doReturn(kotlinx.coroutines.CompletableDeferred(Unit)).`when`(scanner).disposal
        set("channel", channel); set("scanner", scanner)
        val activity = mock(android.app.Activity::class.java)
        val binding = mock(io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding::class.java)
        doReturn(activity).`when`(binding).activity
        doReturn(io.flutter.embedding.engine.plugins.lifecycle.HiddenLifecycleReference(mock(androidx.lifecycle.Lifecycle::class.java)))
            .`when`(binding).lifecycle
        set("activityBinding", binding)
        val permissions = com.dns_technologies.mlkit_scanner.permissions.PermissionGateway(permissionChecker = { _, _ -> true })
        permissions.attach(activity)
        set("permissions", permissions)
    }
    fun set(name: String, value: Any?) { plugin.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(plugin, value) }
    fun call(method: String, arguments: Any? = null): Reply {
        val reply = Reply(); plugin.onMethodCall(MethodCall(method, arguments), reply); return reply
    }
    fun arguments(viewId: Any?, id: String = "capture-${++nextCapture}") = mapOf(
        "viewId" to viewId, "captureId" to id,
        "configuration" to mapOf("zoomRatio" to 1.0, "torchEnabled" to false),
        "geometry" to mapOf("width" to 1.0, "height" to 1.0), "delay" to 0,
    )
    fun open(viewId: Int): String {
        val arguments = arguments(viewId)
        call("capture", arguments)
        return arguments["captureId"] as String
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
