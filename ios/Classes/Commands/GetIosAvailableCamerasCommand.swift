import Flutter

/// Returns supported cameras exposed by AVFoundation.
final class GetIosAvailableCamerasCommand: ScannerCommand {
    private let cameraUtil: CameraUtil

    /// Creates a camera-discovery command for one scanner session.
    init(
        scannerSession: ScannerSession,
        cameraUtil: CameraUtil = CameraUtil()
    ) {
        self.cameraUtil = cameraUtil
        super.init(scannerSession: scannerSession)
    }

    /// Returns JSON-compatible descriptors for supported capture devices.
    override func executeCommand(
        _ call: FlutterMethodCall,
        result: @escaping FlutterResult
    ) throws {
        result(
            cameraUtil.getAvailableCameras()
                .filter { $0.isSupported }
                .map { $0.toCameraData().toJson() }
        )
    }
}
