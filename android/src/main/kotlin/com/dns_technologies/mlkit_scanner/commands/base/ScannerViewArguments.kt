package com.dns_technologies.mlkit_scanner.commands.base

import com.dns_technologies.mlkit_scanner.PluginConstants
import com.dns_technologies.mlkit_scanner.PluginError
import io.flutter.plugin.common.MethodCall

/** Decodes the mandatory platform-view identity at the method-channel boundary. */
internal object ScannerViewArguments {
    fun requireViewId(call: MethodCall): Int {
        val arguments = call.arguments as? Map<*, *> ?: throw PluginError.InvalidArguments
        val value = arguments[PluginConstants.viewIdArgument]
        val viewId = (value as? Number)?.toInt() ?: throw PluginError.InvalidArguments
        if (viewId < 0) throw PluginError.InvalidArguments
        return viewId
    }
}
