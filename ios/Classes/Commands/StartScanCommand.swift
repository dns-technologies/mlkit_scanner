import Flutter

/// Stores and activates barcode recognition for one platform view.
final class StartScanCommand: ScannerCommand {
    /// Parses and activates recognition options for the target view.
    override func executeCommand(
        _ call: FlutterMethodCall,
        result: @escaping FlutterResult
    ) throws {
        let options = try ScannerMethodArguments.scanOptions(call.arguments)
        try scannerSession.startScan(
            viewId: options.viewId,
            type: options.type,
            delay: options.delay
        )
        success(result)
    }
}
