package com.dns_technologies.mlkit_scanner.scanner

import android.content.Context
import com.dns_technologies.mlkit_scanner.scanner.components.analyzer.ImageBarcodeAnalyzer
import com.dns_technologies.mlkit_scanner.scanner.components.camera.Camera
import io.flutter.plugin.common.StandardMessageCodec
import io.flutter.plugin.platform.PlatformView
import io.flutter.plugin.platform.PlatformViewFactory

/** Listener invoked after a scanner platform view is created. */
typealias OnCreateScannerViewListener = (scannerView: ScannerView) -> Unit

/** Listener invoked when a scanner platform view is disposed. */
typealias OnDisposeScannerViewListener = (scannerView: ScannerView) -> Unit

/** Factory callback that creates a concrete camera component. */
typealias CreateCamera = (context: Context) -> Camera

/** Factory callback that creates a concrete barcode analyzer component. */
typealias CreateImageAnalyzer = () -> ImageBarcodeAnalyzer

/**
 * Creates scanner camera platform views.
 */
class ScannerViewFactory(
    private val createCamera: CreateCamera,
    private val createImageAnalyzer: CreateImageAnalyzer,
    private val onCreate: OnCreateScannerViewListener,
    private val onDispose: OnDisposeScannerViewListener,
) : PlatformViewFactory(StandardMessageCodec.INSTANCE) {
    override fun create(context: Context?, viewId: Int, args: Any?): PlatformView {
        val safeContext = context!!
        val scanner = Scanner(
            camera = createCamera(safeContext),
            analyzer = createImageAnalyzer(),
        )
        val scannerView = ScannerView(
            context = safeContext,
            scanner = scanner,
            onDispose = onDispose,
        )
        onCreate.invoke(scannerView)
        return scannerView
    }
}
