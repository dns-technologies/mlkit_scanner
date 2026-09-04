import Flutter

/// Releases camera ownership without deleting the addressed view state.
final class ReleaseCameraCommand: BaseScannerCommand {
    /// Parses the target view and completes after ownership is released.
    func execute(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        do {
            let viewId = try ScannerMethodArguments.viewId(call.arguments)
            scannerSession.releaseCamera(viewId: viewId) {
                self.success(result)
            }
        } catch {
            reportError(result, error: error)
        }
    }
}
