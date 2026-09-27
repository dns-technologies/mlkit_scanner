import Flutter

/// Cancels barcode delivery for the selected scanner.
final class CancelScanCommand: ScannerCommand {

    override func executeCommand(_ call: FlutterMethodCall, result: @escaping FlutterResult) throws {
        try scannerDevice.cancelScan()
        success(result)
    }
}
