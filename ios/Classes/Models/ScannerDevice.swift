import CoreGraphics

/// Completion used by asynchronous camera ownership and lifecycle operations.
typealias ScannerCompletion = (Error?) -> Void

/// Operations exposed by the single iOS scanner SDK bridge shared by platform views.
protocol ScannerDevice: AnyObject {
    /// Creates a native platform view without assigning camera ownership.
    func createView(
        frame: CGRect,
        viewId: Int64
    ) -> ScannerView

    /// Selects the preview and applies the complete transient Dart snapshot.
    func captureCamera(viewId: Int64, configuration: ScannerConfiguration, completion: @escaping ScannerCompletion)
    /// Releases the single scanner after cancelling pending native work.
    func releaseCamera( completion: @escaping () -> Void)
    /// Applies point controls without reacquiring or hiding the preview.
    func setTorch(enabled: Bool) throws
    func setZoomRatio(value: Double) throws
    func setCropArea(cropRect: CropRect) throws
    func updateScanPeriod(delay: Int) throws
    func startScan(type: RecognitionType, delay: Int) throws
    func cancelScan() throws
    /// Releases SDK resources and forgets borrowed Flutter views.
    func release()
}
