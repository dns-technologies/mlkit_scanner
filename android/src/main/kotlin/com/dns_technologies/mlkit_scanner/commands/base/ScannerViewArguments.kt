package com.dns_technologies.mlkit_scanner.commands.base

import com.dns_technologies.mlkit_scanner.PluginConstants
import io.flutter.plugin.common.MethodCall

/** Decodes the platform-view identity used only for view disposal. */
internal object ScannerViewArguments {
    fun viewId(call: MethodCall): Int? {
        val arguments = call.arguments as? Map<*, *> ?: return null
        val value = arguments[PluginConstants.viewIdArgument]
        return (value as? Number)?.toInt()
    }
}
