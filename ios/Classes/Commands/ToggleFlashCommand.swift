import Flutter

/// Applies a point control to the scanner selected by Dart.
final class ToggleFlashCommand: ScannerCommand {
    override func executeCommand(_ call: FlutterMethodCall, result: @escaping FlutterResult) throws {
        let values = call.arguments as? [String: Any]
        let enabled = try PlatformChannelScalar.bool(from: values?[PluginConstants.valueArgument])
        try scannerDevice.setTorch(enabled: enabled)
        success(result)
    }
}
