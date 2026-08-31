import Flutter

/// Updates the camera retained by one platform view.
final class SetIosCameraCommand: ScannerCommand {
    /// Parses and applies a camera selection for the target view.
    override func executeCommand(
        _ call: FlutterMethodCall,
        result: @escaping FlutterResult
    ) throws {
        let value = try ScannerMethodArguments.camera(call.arguments)
        try scannerSession.setCamera(viewId: value.viewId, camera: value.value)
        success(result)
    }
}
