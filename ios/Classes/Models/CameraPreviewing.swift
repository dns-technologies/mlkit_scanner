import AVFoundation
import CoreGraphics

/// Receives camera state changes for the current scanner owner.
protocol CameraPreviewDelegate: AnyObject {
    /// Reports preview metadata from the camera that owns the output.
    func onPreviewChanged(_ camera: CameraPreviewing, description: [String: Any]?)

    /// Forwards torch changes from the currently selected camera.
    func onTorchChanged(_ camera: CameraPreviewing, enabled: Bool)

    /// Whether an active owner is ready for focus control.
    func canApplyFocus() -> Bool
}

/// Camera session and preview-stream operations, independent of view ownership.
protocol CameraPreviewing: AnyObject {
    /// Whether native session initialization has completed.
    var isInitialized: Bool { get }
    /// Whether the selected camera currently reports its torch as active.
    var isTorchActive: Bool { get }
    /// Recognition endpoint used for subsequently submitted camera frames.
    var recognitionHandler: RecognitionHandler? { get set }
    /// Receives changes from the active native camera.
    var cameraPreviewDelegate: CameraPreviewDelegate? { get set }

    /// Initializes the native camera session and reports completion on main.
    func initCamera(completion: @escaping (Error?) -> Void)

    /// Applies capture settings and viewport geometry before starting the stream.
    func prepare(_ settings: ScannerConfiguration, geometry: CGSize, completion: @escaping (Error?) -> Void)

    /// Cancels outstanding preparation and first-frame responses.
    func cancelPendingStart(completion: @escaping () -> Void)

    /// Selects the capture device while preserving the session on failure.
    func setCamera(_ cameraData: CameraData) throws

    /// Applies the requested torch state or reports unsupported hardware.
    func setFlash(_ enabled: Bool) throws

    /// Best-effort restoration of continuous focus before capture.
    func resetFocus()

    /// Applies autofocus and exposure modes at the recognition-area center.
    func focus(locked: Bool) throws

    /// Starts the stream and completes when its first preview frame arrives.
    func resumeCamera(completion: @escaping (Error?) -> Void)

    /// Stops capture while retaining the most recent preview frame.
    func pauseCamera(completion: @escaping () -> Void)

    /// Applies an absolute zoom factor to the selected camera.
    func setZoomRatio(_ value: Double) throws

    /// Updates the normalized area used for focus and recognition.
    func setCropArea(_ cropRect: CropRect)

    /// Updates the viewport used to map preview coordinates into camera frames.
    func updateGeometry(_ size: CGSize)

    /// Releases camera and preview resources before reporting completion.
    func dispose(completion: @escaping () -> Void)
}
