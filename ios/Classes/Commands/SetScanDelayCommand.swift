import Flutter

/// Updates the selected scanner recognition cooldown.
final class SetScanDelayCommand: ScannerCommand {

    override func executeCommand(_ call: FlutterMethodCall, result: @escaping FlutterResult) throws {
        try scannerDevice.updateScanPeriod(delay: ScannerMethodArguments.scanDelay(call.arguments))
        success(result)
    }
}
