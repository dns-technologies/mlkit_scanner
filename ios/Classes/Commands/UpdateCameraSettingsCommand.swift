import Flutter

/// Applies changed camera controls together without reacquiring the camera.
final class UpdateCameraSettingsCommand: ScannerCommand {

    /// Decodes the complete batch before applying settings validated by Dart.
    override func executeCommand(_ call: FlutterMethodCall, result: @escaping FlutterResult) throws {
        let values = try ScannerMethodArguments.map(call.arguments)
        let zoom = try values["zoomRatio"].map {
            try PlatformChannelScalar.finiteDouble(from: $0)
        }
        let torch = try values["torchEnabled"].map { try PlatformChannelScalar.bool(from: $0) }
        let crop = try values["cropRect"].map {
            try ScannerMethodArguments.cropRect([PluginConstants.cropRectArgument: $0])
        }

        if let crop = crop { try scannerDevice.setCropArea(cropRect: crop) }
        if let zoom = zoom { try scannerDevice.setZoomRatio(value: zoom) }
        if let torch = torch { try scannerDevice.setTorch(enabled: torch) }
        success(result)
    }
}
