import Foundation

/// Retained intent and configuration owned by one Flutter platform view.
final class ScannerViewState {
    let viewId: Int64
    let registrationToken: UUID
    weak var view: CameraPreviewing?
    var isCameraOwner = false
    var cameraInitialized = false
    var cameraInitializing = false
    var cameraResuming = false
    var cameraWaitingForLayout = false
    /// True after the previously active capture session has actually stopped.
    var cameraTransitionReady = false
    var cameraRequested = true
    var scanRequestedByView = false
    var recognitionType: RecognitionType?
    var scanDelay: Int?
    var zoomRatio: Double?
    var torchEnabled: Bool?
    var cropArea: CropRect?
    var camera: CameraData?
    var configurationApplied = false
    var recognitionHandler: RecognitionHandler?
    var captureCompletions: [ScannerSessionCompletion] = []

    /// Creates retained state from one registered native platform view.
    init(
        viewId: Int64,
        view: CameraPreviewing,
        registration: ScannerViewRegistration
    ) {
        self.viewId = viewId
        registrationToken = view.registrationToken
        self.view = view
        zoomRatio = registration.initialZoomRatio
        torchEnabled = registration.initialFlashEnabled
        cropArea = registration.initialCropRect
        camera = registration.initialCamera
    }
}
