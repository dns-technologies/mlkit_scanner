import Flutter

/// Applies a point control to the scanner selected by Dart.
final class SetCropAreaCommand: ScannerCommand {
    override func executeCommand(_ call: FlutterMethodCall, result: @escaping FlutterResult) throws {
        try scannerDevice.setCropArea(cropRect: ScannerMethodArguments.cropRect(call.arguments))
        success(result)
    }
}
