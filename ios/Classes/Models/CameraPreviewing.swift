import Foundation
import UIKit

/// Replaceable AVFoundation boundary for the native SDK bridge.
/// Hardware callbacks and resource cleanup can be tested without opening a camera.
protocol CameraPreviewing: AnyObject {
    /// Native preview moved between Flutter containers.
    func view() -> UIView
    /// Whether the capture session and video output have both been configured.
    var isInitialized: Bool { get }
    /// Current physical torch activity, not retained intent.
    var isTorchActive: Bool { get }
    /// Completes layout/start waiters without stopping a reusable capture session.
    func cancelPendingStart(completion: @escaping () -> Void)

    /// Recognition handler receiving frames while scanning is active.
    var recognitionHandler: RecognitionHandler? { get set }

    /// Delegate for physical focus admission and torch observation.
    var cameraPreviewDelegate: CameraPreviewDelegate? { get set }

    /// Whether the native preview currently has finite, nonempty bounds.
    var isLayoutReady: Bool { get }

    /// Calls `completion` when the native preview first has usable bounds.
    func whenLayoutReady(_ completion: @escaping () -> Void)

    /// Prepares capture resources after the bridge has obtained permission.
    func initCamera(completion: @escaping (Error?) -> Void)

    /// Replaces the selected capture device.
    func setCamera(_ cameraData: CameraData) throws

    /// Applies an explicit torch state.
    func setFlash(_ enabled: Bool) throws

    /// Clears retained focus lock and restores continuous focus where supported.
    func resetFocus()

    /// Starts capture and completes after the first video frame arrives.
    func resumeCamera(completion: @escaping (Error?) -> Void)

    /// Applies an absolute camera zoom ratio.
    func setZoomRatio(_ value: Double) throws

    /// Updates the recognition rectangle and its focus center.
    func setCropArea(_ cropRect: CropRect)

    /// Updates whether the scanner overlay indicates active recognition.
    func setScanActive(_ isActive: Bool)

    /// Releases all resources owned by this preview.
    func dispose()
}
