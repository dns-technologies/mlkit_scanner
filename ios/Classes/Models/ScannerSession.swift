import CoreGraphics

/// Completion used by asynchronous camera ownership and lifecycle operations.
typealias ScannerSessionCompletion = (Error?) -> Void

/// Operations exposed by the single iOS scanner session shared by platform views.
protocol ScannerSession: AnyObject {
    func createView(
        frame: CGRect,
        viewId: Int64,
        registration: ScannerViewRegistration
    ) -> CameraPreview

    func captureCamera(viewId: Int64, completion: @escaping ScannerSessionCompletion)
    func releaseCamera(viewId: Int64, completion: @escaping () -> Void)
    func pauseCamera(viewId: Int64, completion: @escaping () -> Void)
    func resumeCamera(viewId: Int64, completion: @escaping ScannerSessionCompletion)
    func toggleFlash(viewId: Int64) throws
    func startScan(viewId: Int64, type: RecognitionType, delay: Int) throws
    func cancelScan(viewId: Int64)
    func updateScanPeriod(viewId: Int64, delay: Int) throws
    func setZoom(viewId: Int64, value: Double) throws
    func setCropArea(viewId: Int64, cropRect: CropRect) throws
    func setCamera(viewId: Int64, camera: CameraData) throws
    func release()
}
