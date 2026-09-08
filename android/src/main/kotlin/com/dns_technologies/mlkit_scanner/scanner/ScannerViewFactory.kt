package com.dns_technologies.mlkit_scanner.scanner

import android.content.Context
import com.dns_technologies.mlkit_scanner.PluginError
import com.dns_technologies.mlkit_scanner.utils.requireInt
import com.dns_technologies.mlkit_scanner.utils.requireMap
import io.flutter.plugin.common.StandardMessageCodec
import io.flutter.plugin.platform.PlatformView
import io.flutter.plugin.platform.PlatformViewFactory

/** Validates a UI registration address; all scanner configuration arrives from Dart at capture. */
internal class ScannerViewFactory(
    private val createView: (Context, Int) -> ScannerView,
) : PlatformViewFactory(StandardMessageCodec.INSTANCE) {
    override fun create(context: Context?, viewId: Int, args: Any?): PlatformView {
        val viewContext = requireNotNull(context) { "Flutter did not provide a platform-view context" }
        if (viewId < 0 || args.requireMap().requireInt("viewId") != viewId) throw PluginError.InvalidArguments
        return createView(viewContext, viewId)
    }
}
