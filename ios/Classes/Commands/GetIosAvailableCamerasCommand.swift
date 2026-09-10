import Flutter

/// Returns cameras supported by the native scanner.
final class GetIosAvailableCamerasCommand: ScannerCommand {
    private let cameraUtil: CameraUtil

    /// Creates a camera-discovery command for the native scanner.
    init(
        scannerDevice: ScannerDevice,
        cameraUtil: CameraUtil = CameraUtil()
    ) {
        self.cameraUtil = cameraUtil
        super.init(scannerDevice: scannerDevice)
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
