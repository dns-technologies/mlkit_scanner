import CoreGraphics

/// Completes a native scanner request with an optional failure.
typealias ScannerCompletion = (Error?) -> Void

/// Native controls for the shared scanner; widget configuration remains in Dart.
protocol ScannerDevice: AnyObject {

    /// Acquires the shared camera for a registered consumer using its settings.
    func captureCamera(viewId: Int64, configuration: ScannerConfiguration, completion: @escaping ScannerCompletion)

    /// Revokes the active consumer while retaining the shared camera.
    func releaseCamera(completion: @escaping () -> Void)

    /// Applies the desired torch state to the active camera.
    func setTorch(enabled: Bool) throws

    /// Applies an absolute zoom factor to the selected camera.
    func setZoomRatio(value: Double) throws

    /// Updates the normalized area used for focus and recognition.
    func setCropArea(cropRect: CropRect) throws

    /// Changes the cooldown after successful barcode recognition.
    func updateScanPeriod(delay: Int) throws

    /// Enables barcode delivery with the requested recognition cooldown.
    func startScan(type: RecognitionType, delay: Int) throws

    /// Cancels the current barcode subscription and suppresses queued deliveries.
    func cancelScan() throws

    /// Permanently releases scanner ownership, observers, and camera resources.
    func release()
}
