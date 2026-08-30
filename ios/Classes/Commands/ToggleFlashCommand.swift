import Flutter

/// Toggles the torch state retained by one platform view.
final class ToggleFlashCommand: ScannerCommand {
    override func executeCommand(
        _ call: FlutterMethodCall,
        result: @escaping FlutterResult
    ) throws {
        try scannerSession.toggleFlash(
            viewId: ScannerMethodArguments.viewId(call.arguments)
        )
        success(result)
    }
}
