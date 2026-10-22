import Flutter

/// Updates the selected camera zoom ratio.
final class SetZoomRatioCommand: ScannerCommand {

    override func executeCommand(_ call: FlutterMethodCall, result: @escaping FlutterResult) throws {
        try scanner.setZoomRatio(value: requireMap(call.arguments).requireFiniteDouble(PluginConstants.valueArgument))
        success(result)
    }
}
