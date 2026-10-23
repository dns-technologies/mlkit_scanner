import Flutter

/// Updates the selected scanner recognition area.
final class SetCropAreaCommand: ScannerCommand {

    override func executeCommand(_ call: FlutterMethodCall, result: @escaping FlutterResult) throws {
        let values = try ArgumentUtils.requireMap(call.arguments).requireMap(PluginConstants.cropRectArgument)
        try scanner.setCropArea(cropRect: CropRect(arguments: values))
        success(result)
    }
}
