import Flutter

/// Updates the selected camera zoom ratio.
final class SetZoomRatioCommand: ScannerCommand {

    override func executeCommand(_ call: FlutterMethodCall, result: @escaping FlutterResult) throws {
        try scanner.setZoomRatio(value: ArgumentUtils.requireMap(call.arguments).requireFiniteDouble("value"))
        success(result)
    }
}
