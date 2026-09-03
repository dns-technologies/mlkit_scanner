import Flutter
import Foundation

/// Shared command functionality for one scanner session.
class BaseScannerCommand {
    /// Scanner session targeted by this command.
    let scannerSession: ScannerSession

    /// Creates command functionality for one scanner session.
    init(scannerSession: ScannerSession) {
        self.scannerSession = scannerSession
    }

    /// Completes a Flutter call successfully without a return value.
    func success(_ result: @escaping FlutterResult) {
        result(nil)
    }

    /// Completes a Flutter call successfully or maps the supplied error.
    func complete(_ result: @escaping FlutterResult, error: Error?) {
        guard let error = error else {
            success(result)
            return
        }
        reportError(result, error: error)
    }

    /// Converts a native error into a stable Flutter platform error.
    func reportError(_ result: @escaping FlutterResult, error: Error) {
        if let controlError = error as? CameraControlError {
            result(FlutterError(
                code: CameraControlError.errorCode,
                message: CameraControlError.errorMessage,
                details: controlError.channelDetails
            ))
        } else if let pluginError = error as? MlKitPluginError {
            result(FlutterError(
                code: pluginError.rawValue,
                message: pluginError.localizedDescription,
                details: nil
            ))
        } else {
            result(FlutterError(
                code: MlKitPluginError.unknownError.rawValue,
                message: error.localizedDescription,
                details: nil
            ))
        }
    }
}
