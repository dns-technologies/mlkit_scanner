import Flutter

/// Starts barcode delivery for the selected scanner.
final class StartScanCommand: ScannerCommand {

    override func executeCommand(_ call: FlutterMethodCall, result: @escaping FlutterResult) throws {
        try scanner.startScan(delay: requireMap(call.arguments).requireInt(PluginConstants.delayArgument))
        success(result)
    }
}
