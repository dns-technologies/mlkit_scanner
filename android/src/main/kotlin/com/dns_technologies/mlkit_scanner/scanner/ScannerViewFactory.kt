package com.dns_technologies.mlkit_scanner.scanner

import android.content.Context
import io.flutter.plugin.common.StandardMessageCodec
import io.flutter.plugin.platform.PlatformView
import io.flutter.plugin.platform.PlatformViewFactory

/** Creates a scanner platform view for the supplied Flutter platform-view id. */
typealias CreateScannerView = (context: Context, viewId: Int) -> ScannerView

/**
 * Creates scanner camera platform views.
 *
 * @property createScannerView Session-aware factory invoked for each Flutter platform-view id.
 */
class ScannerViewFactory(
    private val createScannerView: CreateScannerView,
) : PlatformViewFactory(StandardMessageCodec.INSTANCE) {
    /** Creates a platform view registered in the shared scanner session. */
    override fun create(context: Context?, viewId: Int, args: Any?): PlatformView {
        return createScannerView(
            requireNotNull(context) { "Flutter did not provide a platform-view context" },
            viewId,
        )
    }
}
