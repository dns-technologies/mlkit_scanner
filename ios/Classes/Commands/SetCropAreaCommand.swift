import Flutter

/// Updates recognition crop area retained by one platform view.
final class SetCropAreaCommand: ScannerCommand {
    /// Parses and applies normalized crop geometry for the target view.
    override func executeCommand(
        _ call: FlutterMethodCall,
        result: @escaping FlutterResult
    ) throws {
        let value = try ScannerMethodArguments.cropRect(call.arguments)
        try scannerSession.setCropArea(
            viewId: value.viewId,
            cropRect: value.value
        )
        success(result)
    }
}
