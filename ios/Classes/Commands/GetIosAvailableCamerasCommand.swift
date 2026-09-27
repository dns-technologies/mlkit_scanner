import Flutter

/// Returns cameras supported by the native scanner.
final class GetIosAvailableCamerasCommand: ScannerCommand {
    /// Discovers native camera devices for this command.
    private let cameraUtil: CameraUtil

    init(
        scannerDevice: ScannerDevice,
        cameraUtil: CameraUtil = CameraUtil()
    ) {
        self.cameraUtil = cameraUtil
        super.init(scannerDevice: scannerDevice)
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
