import Flutter

/// Updates normalized zoom retained by one platform view.
final class SetZoomCommand: ScannerCommand {
    override func executeCommand(
        _ call: FlutterMethodCall,
        result: @escaping FlutterResult
    ) throws {
        let value = try ScannerMethodArguments.zoom(call.arguments)
        try scannerSession.setZoom(viewId: value.viewId, value: value.value)
        success(result)
    }
}
