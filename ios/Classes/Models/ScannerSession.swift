import CoreGraphics

/// Completion used by asynchronous camera ownership and lifecycle operations.
typealias ScannerSessionCompletion = (Error?) -> Void

/// Operations exposed by the single iOS scanner session shared by platform views.
protocol ScannerSession: AnyObject {
    /// Creates a native platform view without assigning camera ownership.
    func createView(
        frame: CGRect,
        viewId: Int64,
        registration: ScannerViewRegistration
    ) -> CameraPreview

    /// Transfers camera ownership to a registered view and restores its state.
    func captureCamera(viewId: Int64, completion: @escaping ScannerSessionCompletion)
    /// Releases ownership only when it is still held by the referenced view.
    func releaseCamera(viewId: Int64, completion: @escaping () -> Void)
    /// Pauses camera work requested by a view without deleting its state.
    func pauseCamera(viewId: Int64, completion: @escaping () -> Void)
    /// Resumes retained camera and recognition intent for the owning view.
    func resumeCamera(viewId: Int64, completion: @escaping ScannerSessionCompletion)
    /// Toggles the retained torch state of a ready view.
    func toggleFlash(viewId: Int64) throws
    /// Starts recognition with the platform-specific initial and result cooldown.
    func startScan(viewId: Int64, type: RecognitionType, delay: Int) throws
    /// Stops recognition requested by a view without pausing its camera.
    func cancelScan(viewId: Int64)
    /// Updates the successful-recognition cooldown retained by a view.
    func updateScanPeriod(viewId: Int64, delay: Int) throws
    /// Updates normalized zoom retained by a view.
    func setZoom(viewId: Int64, value: Double) throws
    /// Updates normalized recognition geometry retained by a view.
    func setCropArea(viewId: Int64, cropRect: CropRect) throws
    /// Updates the camera retained by a view.
    func setCamera(viewId: Int64, camera: CameraData) throws
    /// Releases all views, recognition handlers, and camera resources.
    func release()
}
