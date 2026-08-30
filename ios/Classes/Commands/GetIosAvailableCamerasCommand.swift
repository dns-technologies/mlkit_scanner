import Flutter

/// Returns supported cameras exposed by AVFoundation.
final class GetIosAvailableCamerasCommand: ScannerCommand {
    private let cameraUtil: CameraUtil

    init(
        scannerSession: ScannerSession,
        cameraUtil: CameraUtil = CameraUtil()
    ) {
        self.cameraUtil = cameraUtil
        super.init(scannerSession: scannerSession)
    }

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
