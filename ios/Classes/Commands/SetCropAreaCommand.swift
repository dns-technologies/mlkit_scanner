import Flutter

/// Updates the selected scanner recognition area.
final class SetCropAreaCommand: ScannerCommand {

    /// Validates and applies the normalized recognition area.
    override func executeCommand(_ call: FlutterMethodCall, result: @escaping FlutterResult) throws {
        try scannerDevice.setCropArea(cropRect: ScannerMethodArguments.cropRect(call.arguments))
        success(result)
    }
}
