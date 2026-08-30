import Flutter

/// Updates recognition delay retained by one platform view.
final class SetScanDelayCommand: ScannerCommand {
    override func executeCommand(
        _ call: FlutterMethodCall,
        result: @escaping FlutterResult
    ) throws {
        let value = try ScannerMethodArguments.scanDelay(call.arguments)
        try scannerSession.updateScanPeriod(
            viewId: value.viewId,
            delay: value.value
        )
        success(result)
    }
}
