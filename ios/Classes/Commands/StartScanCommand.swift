import Flutter

/// Stores and activates barcode recognition for one platform view.
final class StartScanCommand: ScannerCommand {
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
