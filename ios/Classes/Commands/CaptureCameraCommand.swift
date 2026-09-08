import Flutter

/// Transfers camera ownership to one registered iOS platform view.
final class CaptureCameraCommand: BaseScannerCommand {
    /// Parses the target view and completes after camera capture finishes.
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
