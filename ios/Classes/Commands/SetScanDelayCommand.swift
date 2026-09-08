import Flutter

/// Applies a point control to the scanner selected by Dart.
final class SetScanDelayCommand: ScannerCommand {
    override func executeCommand(_ call: FlutterMethodCall, result: @escaping FlutterResult) throws {
        try scannerDevice.updateScanPeriod(delay: ScannerMethodArguments.scanDelay(call.arguments))
        success(result)
    }
}
