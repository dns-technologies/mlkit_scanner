import Flutter

/// Clears recognition intent for one platform view.
final class CancelScanCommand: ScannerCommand {
    override func executeCommand(
        _ call: FlutterMethodCall,
        result: @escaping FlutterResult
    ) throws {
        scannerSession.cancelScan(
            viewId: try ScannerMethodArguments.viewId(call.arguments)
        )
        success(result)
    }
}
