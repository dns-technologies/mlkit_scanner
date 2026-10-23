import Flutter

/// Updates the selected scanner recognition cooldown.
final class SetScanDelayCommand: ScannerCommand {

    override func executeCommand(_ call: FlutterMethodCall, result: @escaping FlutterResult) throws {
        try scanner.updateScanPeriod(delay: ArgumentUtils.requireMap(call.arguments).requireInt("delay"))
        success(result)
    }
}
