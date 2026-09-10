import Flutter

/// Applies a point control to the scanner selected by Dart.
final class StartScanCommand: ScannerCommand {
    override func executeCommand(_ call: FlutterMethodCall, result: @escaping FlutterResult) throws {
        let options = try ScannerMethodArguments.scanOptions(call.arguments)
        try scannerDevice.startScan(type: options.type, delay: options.delay)
        success(result)
    }
}
