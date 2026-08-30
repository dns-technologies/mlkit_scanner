import Flutter
import Foundation

/// Shared command functionality for one scanner session.
class BaseScannerCommand {
    let scannerSession: ScannerSession

    init(scannerSession: ScannerSession) {
        self.scannerSession = scannerSession
    }

    func success(_ result: @escaping FlutterResult) {
        result(nil)
    }

    func complete(_ result: @escaping FlutterResult, error: Error?) {
        guard let error = error else {
            success(result)
            return
        }
        reportError(result, error: error)
    }

    func reportError(_ result: @escaping FlutterResult, error: Error) {
        if let pluginError = error as? MlKitPluginError {
            result(FlutterError(
                code: pluginError.rawValue,
                message: pluginError.localizedDescription,
                details: nil
            ))
        } else {
            result(FlutterError(
                code: "0",
                message: error.localizedDescription,
                details: nil
            ))
        }
    }
}
