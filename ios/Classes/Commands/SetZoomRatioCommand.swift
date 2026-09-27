import Flutter

/// Updates the selected camera zoom ratio.
final class SetZoomRatioCommand: ScannerCommand {

    override func executeCommand(_ call: FlutterMethodCall, result: @escaping FlutterResult) throws {
        try scannerDevice.setZoomRatio(value: ScannerMethodArguments.zoomRatio(call.arguments))
        success(result)
    }
}
