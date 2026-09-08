import Flutter

/// Applies a point control to the scanner selected by Dart.
final class CancelScanCommand: ScannerCommand {
    override func executeCommand(_ call: FlutterMethodCall, result: @escaping FlutterResult) throws {
        try scannerDevice.cancelScan()
        success(result)
    }
}
