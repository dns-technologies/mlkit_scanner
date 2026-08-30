import Flutter

/// Base command abstraction with shared synchronous error handling.
class ScannerCommand: BaseScannerCommand {
    final func execute(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        do {
            try executeCommand(call, result: result)
        } catch {
            reportError(result, error: error)
        }
    }

    func executeCommand(
        _ call: FlutterMethodCall,
        result: @escaping FlutterResult
    ) throws {
        preconditionFailure("ScannerCommand subclasses must implement executeCommand")
    }
}
