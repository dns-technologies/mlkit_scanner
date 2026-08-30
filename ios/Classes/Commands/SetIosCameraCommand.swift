import Flutter

/// Updates the AVFoundation camera retained by one platform view.
final class SetIosCameraCommand: ScannerCommand {
    override func executeCommand(
        _ call: FlutterMethodCall,
        result: @escaping FlutterResult
    ) throws {
        let value = try ScannerMethodArguments.camera(call.arguments)
        try scannerSession.setCamera(viewId: value.viewId, camera: value.value)
        success(result)
    }
}
