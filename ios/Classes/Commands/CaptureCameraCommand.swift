import Flutter

/// Transfers camera ownership to one registered iOS platform view.
final class CaptureCameraCommand: BaseScannerCommand {
    func execute(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        do {
            let viewId = try ScannerMethodArguments.viewId(call.arguments)
            scannerSession.captureCamera(viewId: viewId) { error in
                self.complete(result, error: error)
            }
        } catch {
            reportError(result, error: error)
        }
    }
}
