import Flutter

/// Restores the addressed platform view's camera intent and retained controls.
final class ResumeCameraCommand: BaseScannerCommand {
    func execute(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        do {
            let viewId = try ScannerMethodArguments.viewId(call.arguments)
            scannerSession.resumeCamera(viewId: viewId) { error in
                self.complete(result, error: error)
            }
        } catch {
            reportError(result, error: error)
        }
    }
}
