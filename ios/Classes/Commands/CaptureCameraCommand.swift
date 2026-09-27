import Flutter

/// Acquires camera capture for a registered consumer.
final class CaptureCameraCommand: BaseScannerCommand {

    /// Acquires camera capture with the requested settings.
    func execute(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        do {
            let viewId = try ScannerMethodArguments.viewId(call.arguments)
            let values = call.arguments as? [String: Any]
            let configuration = try ScannerConfiguration(arguments: values?["configuration"])
            scannerDevice.captureCamera(viewId: viewId, configuration: configuration) { error in
                self.complete(result, error: error)
            }
        } catch {
            reportError(result, error: error)
        }
    }
}
