import Flutter

/// Applies the desired torch state to the selected camera.
final class ToggleFlashCommand: ScannerCommand {

    /// Validates and applies the desired torch state.
    override func executeCommand(_ call: FlutterMethodCall, result: @escaping FlutterResult) throws {
        let values = call.arguments as? [String: Any]
        let enabled = try PlatformChannelScalar.bool(from: values?[PluginConstants.valueArgument])
        try scannerDevice.setTorch(enabled: enabled)
        success(result)
    }
}
