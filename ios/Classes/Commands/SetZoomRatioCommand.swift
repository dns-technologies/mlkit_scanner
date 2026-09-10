import Flutter

/// Applies a point control to the scanner selected by Dart.
final class SetZoomRatioCommand: ScannerCommand {
    override func executeCommand(_ call: FlutterMethodCall, result: @escaping FlutterResult) throws {
        try scannerDevice.setZoomRatio(value: ScannerMethodArguments.zoomRatio(call.arguments))
        success(result)
    }
}
