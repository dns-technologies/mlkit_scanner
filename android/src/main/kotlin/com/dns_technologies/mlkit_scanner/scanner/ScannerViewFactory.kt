package com.dns_technologies.mlkit_scanner.scanner

import android.content.Context
import io.flutter.plugin.common.StandardMessageCodec
import io.flutter.plugin.platform.PlatformView
import io.flutter.plugin.platform.PlatformViewFactory

/** Creates a scanner platform view for the supplied Flutter platform-view id. */
typealias CreateScannerView = (context: Context, viewId: Int, creationParams: Any?) -> ScannerView

/**
 * Creates scanner camera platform views.
 *
 * @property createScannerView Creates a view from the supplied context, id and creation arguments.
 */
class ScannerViewFactory(
    private val createScannerView: CreateScannerView,
) : PlatformViewFactory(StandardMessageCodec.INSTANCE) {
    /** Delegates view creation without assuming how the caller registers or owns the view. */
    override fun create(context: Context?, viewId: Int, args: Any?): PlatformView {
        return createScannerView(
            requireNotNull(context) { "Flutter did not provide a platform-view context" },
            viewId,
            args,
        )
    }
}
