import Foundation

/// Retained intent and configuration owned by one Flutter platform view.
final class ScannerViewState {
    let viewId: Int64
    weak var view: CameraPreview?
    var isCameraOwner = false
    var cameraStarted = false
    var cameraInitializing = false
    var cameraRequested = true
    var scanRequestedByView = false
    var recognitionType: RecognitionType?
    var scanDelay: Int?
    var zoom: Double?
    var torchEnabled: Bool?
    var cropArea: CropRect?
    var camera: CameraData?
    var configurationApplied = false
    var recognitionHandler: RecognitionHandler?
    var scannerOverlay: ScannerOverlay?
    var captureCompletions: [ScannerSessionCompletion] = []

    init(
        viewId: Int64,
        view: CameraPreview,
        registration: ScannerViewRegistration
    ) {
        self.viewId = viewId
        self.view = view
        zoom = registration.initialZoom
        torchEnabled = registration.initialFlashEnabled
        cropArea = registration.initialCropRect
        camera = registration.initialCamera
    }
}
