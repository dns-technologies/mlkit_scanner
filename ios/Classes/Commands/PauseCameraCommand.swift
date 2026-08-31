import Flutter

/// Stores a paused camera intent for the addressed platform view.
final class PauseCameraCommand: BaseScannerCommand {
    /// Parses the target view and completes after camera work pauses.
    func execute(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        do {
            let viewId = try ScannerMethodArguments.viewId(call.arguments)
            scannerSession.pauseCamera(viewId: viewId) {
                self.success(result)
            }
        } catch {
            reportError(result, error: error)
        }
    }
}
