import Flutter

/// Applies the desired torch state to the selected camera.
final class ToggleFlashCommand: ScannerCommand {

    override func executeCommand(_ call: FlutterMethodCall, result: @escaping FlutterResult) throws {
        let values = try ArgumentUtils.requireMap(call.arguments)
        let enabled = try values.requireBoolean(PluginConstants.valueArgument)
        try scanner.setTorch(enabled: enabled)
        success(result)
    }
}
