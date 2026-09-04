import Flutter
import XCTest
@testable import mlkit_scanner

final class ScannerCommandTests: XCTestCase {
    func testExecuteReturnsSuccessfulCommandValue() {
        let command = TestScannerCommand(error: nil)
        var value: Any? = true

        command.execute(FlutterMethodCall(methodName: "test", arguments: nil)) {
            value = $0
        }

        XCTAssertNil(value)
    }

    func testExecuteMapsThrownCommandError() {
        let command = TestScannerCommand(error: MlKitPluginError.invalidArguments)
        var value: Any?

        command.execute(FlutterMethodCall(methodName: "test", arguments: nil)) {
            value = $0
        }

        let error = value as? FlutterError
        XCTAssertEqual(error?.code, MlKitPluginError.invalidArguments.rawValue)
        XCTAssertEqual(error?.message, MlKitPluginError.invalidArguments.localizedDescription)
    }

    private final class TestScannerCommand: ScannerCommand {
        private let error: Error?

        init(error: Error?) {
            self.error = error
            super.init(scannerSession: RecordingScannerSession())
        }

        override func executeCommand(
            _ call: FlutterMethodCall,
            result: @escaping FlutterResult
        ) throws {
            if let error = error {
                throw error
            }
            success(result)
        }
    }
}
