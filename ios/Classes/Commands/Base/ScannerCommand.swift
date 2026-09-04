import Flutter

/// Base command abstraction with shared synchronous error handling.
class ScannerCommand: BaseScannerCommand {
    /// Executes a command and reports any thrown validation or scanner error.
    final func execute(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        do {
            try executeCommand(call, result: result)
        } catch {
            reportError(result, error: error)
        }
    }

    /// Performs subclass-specific command work after common error handling.
    func executeCommand(
        _ call: FlutterMethodCall,
        result: @escaping FlutterResult
    ) throws {
        preconditionFailure("ScannerCommand subclasses must implement executeCommand")
    }
}
