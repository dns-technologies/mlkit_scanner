import Flutter

/// Starts barcode delivery for the selected scanner.
final class StartScanCommand: ScannerCommand {

    /// Validates recognition options and starts barcode delivery.
    override func executeCommand(_ call: FlutterMethodCall, result: @escaping FlutterResult) throws {
        let options = try ScannerMethodArguments.scanOptions(call.arguments)
        try scannerDevice.startScan(type: options.type, delay: options.delay)
        success(result)
    }
}
