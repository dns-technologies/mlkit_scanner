import Flutter

/// Applies changed camera controls together without reacquiring the camera.
final class UpdateCameraSettingsCommand: ScannerCommand {

    override func executeCommand(_ call: FlutterMethodCall, result: @escaping FlutterResult) throws {
        let values = try requireMap(call.arguments)
        let zoom = values.keys.contains("zoomRatio") ? try values.requireFiniteDouble("zoomRatio") : nil
        let torch = values.keys.contains("torchEnabled") ? try values.requireBoolean("torchEnabled") : nil
        let crop = values.keys.contains("cropRect")
            ? try CropRect(arguments: values.requireMap("cropRect")) : nil

        if let crop = crop { try scanner.setCropArea(cropRect: crop) }
        if let zoom = zoom { try scanner.setZoomRatio(value: zoom) }
        if let torch = torch { try scanner.setTorch(enabled: torch) }
        success(result)
    }
}
