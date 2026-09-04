import Flutter

/// Updates the absolute zoom ratio retained by one platform view.
final class SetZoomRatioCommand: ScannerCommand {
    /// Parses and applies an absolute zoom ratio for the target view.
    override func executeCommand(
        _ call: FlutterMethodCall,
        result: @escaping FlutterResult
    ) throws {
        let value = try ScannerMethodArguments.zoomRatio(call.arguments)
        try scannerSession.setZoomRatio(viewId: value.viewId, value: value.value)
        success(result)
    }
}
