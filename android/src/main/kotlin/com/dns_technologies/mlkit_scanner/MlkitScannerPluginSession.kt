package com.dns_technologies.mlkit_scanner

import com.dns_technologies.mlkit_scanner.scanner.CreateCamera
import com.dns_technologies.mlkit_scanner.scanner.CreateImageAnalyzer
import com.dns_technologies.mlkit_scanner.scanner.ScannerView
import com.dns_technologies.mlkit_scanner.scanner.ScannerViewFactory
import com.dns_technologies.mlkit_scanner.scanner.models.Barcode

/** Owns the active scanner view and its scan-result subscription. */
internal class MlkitScannerPluginSession(
    private val onScan: (Barcode) -> Unit,
) {
    private var unsubscribeFromScanResults: (() -> Unit)? = null

    /** Active scanner platform view, if Flutter has created one. */
    var scannerView: ScannerView? = null
        private set

    /** Indicates whether host lifecycle resume should automatically resume the scanner camera. */
    var isAutoResumeEnabled = true

    /** Creates a platform view factory wired to this session as the scanner view owner. */
    fun createViewFactory(
        createCamera: CreateCamera,
        createImageAnalyzer: CreateImageAnalyzer,
        onDisposeActiveView: () -> Unit,
    ): ScannerViewFactory {
        return ScannerViewFactory(
            createCamera = createCamera,
            createImageAnalyzer = createImageAnalyzer,
            onCreate = ::attach,
            onDispose = { scannerView ->
                if (owns(scannerView)) {
                    onDisposeActiveView.invoke()
                }
            },
        )
    }

    /** Stores a created scanner view and subscribes to scan results. */
    private fun attach(scannerView: ScannerView) {
        this.scannerView = scannerView
        unsubscribeFromScanResults?.invoke()
        unsubscribeFromScanResults = scannerView.subscribeToScanResults(onScan)
    }

    /** Returns true when the disposed view is the currently active scanner view. */
    private fun owns(scannerView: ScannerView): Boolean = this.scannerView === scannerView

    /** Releases scanner resources and clears the active session. */
    fun release() {
        unsubscribeFromScanResults?.invoke()
        unsubscribeFromScanResults = null
        scannerView?.releaseCamera()
        scannerView = null
    }

    /** Returns the active scanner view, or null when scanner camera is not initialized. */
    fun activeScannerViewOrNull(): ScannerView? = scannerView?.takeIf { it.isActive() }
}
