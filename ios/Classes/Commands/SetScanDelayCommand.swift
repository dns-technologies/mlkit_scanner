import Flutter

/// Updates the selected scanner recognition cooldown.
final class SetScanDelayCommand: ScannerCommand {

    /// Validates and applies the successful-recognition cooldown.
    override func executeCommand(_ call: FlutterMethodCall, result: @escaping FlutterResult) throws {
        try scannerDevice.updateScanPeriod(delay: ScannerMethodArguments.scanDelay(call.arguments))
        success(result)
    }
}
