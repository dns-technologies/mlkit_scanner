package com.dns_technologies.mlkit_scanner.scanner

import android.content.Context
import com.dns_technologies.mlkit_scanner.scanner.components.analyzer.ImageBarcodeAnalyzer
import com.dns_technologies.mlkit_scanner.scanner.components.camera.Camera
import com.dns_technologies.mlkit_scanner.scanner.components.ui.focus.FocusView
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

/** Factory callback that creates a focus overlay view. */
typealias CreateFocusView = (context: Context) -> FocusView

/**
 * Creates scanner camera platform views.
 */
class ScannerViewFactory(
    private val createCamera: CreateCamera,
    private val createImageAnalyzer: CreateImageAnalyzer,
    private val createFocusView: CreateFocusView,
    private val onCreate: OnCreateScannerViewListener,
    private val onDispose: OnDisposeScannerViewListener,
) : PlatformViewFactory(StandardMessageCodec.INSTANCE) {
    override fun create(context: Context?, viewId: Int, args: Any?): PlatformView {
        val safeContext = context!!
        val camera = createCamera(safeContext)
        val scanner = Scanner(
            camera = camera,
            analyzer = createImageAnalyzer(),
        )
        val scannerView = ScannerView(
            context = safeContext,
            scanner = scanner,
            focusView = createFocusView(safeContext),
            onDispose = onDispose,
        )
        onCreate.invoke(scannerView)
        return scannerView
    }
}
