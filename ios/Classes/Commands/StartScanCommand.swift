import Flutter

/// Starts barcode delivery for the selected scanner.
final class StartScanCommand: ScannerCommand {

    override func executeCommand(_ call: FlutterMethodCall, result: @escaping FlutterResult) throws {
        let options = try ScannerMethodArguments.scanOptions(call.arguments)
        try scanner.startScan(type: options.type, delay: options.delay)
        success(result)
    }
}
