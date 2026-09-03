import Foundation
import UIKit

/// Camera-preview behavior coordinated by a scanner session.
///
/// The protocol keeps session ownership and race handling independent from
/// AVFoundation so those guarantees can be exercised without opening hardware.
protocol CameraPreviewing: AnyObject {
    /// Flutter identifier of the platform view.
    var viewId: Int64 { get }

    /// Identity of this concrete registration, even if Flutter later reuses an id.
    var registrationToken: UUID { get }

    /// Recognition handler receiving frames while scanning is active.
    var recognitionHandler: RecognitionHandler? { get set }

    /// Delegate receiving focus and torch events scoped to this view.
    var cameraPreviewDelegate: CameraPreviewDelegate? { get set }

    /// Whether the native preview currently has finite, nonempty bounds.
    var isLayoutReady: Bool { get }

    /// Calls `completion` when the native preview first has usable bounds.
    func whenLayoutReady(_ completion: @escaping () -> Void)

    /// Requests permission and prepares capture resources without starting them.
    func initCamera(completion: @escaping (Error?) -> Void)

    /// Replaces the selected capture device.
    func setCamera(_ cameraData: CameraData) throws

    /// Applies an explicit torch state.
    func setFlash(_ enabled: Bool) throws

    /// Clears retained focus lock and restores continuous focus where supported.
    func resetFocus()

    /// Stops capture without releasing reusable resources.
    func pauseCamera(completion: @escaping () -> Void)

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
