import Flutter
import XCTest
@testable import mlkit_scanner

final class ScannerCommandTests: XCTestCase {
    private final class TestScannerCommand: ScannerCommand {
        private let fixture: ScannerCommandFixture
        private let error: Error?

        init(error: Error?) throws {
            let fixture = try ScannerCommandFixture(activate: false)
            self.fixture = fixture
            self.error = error
            super.init(scanner: fixture.scanner)
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

    func testExecuteReturnsSuccessfulCommandValue() throws {
        let command = try TestScannerCommand(error: nil)
        var value: Any? = true

        command.execute(FlutterMethodCall(methodName: "test", arguments: nil)) {
            value = $0
        }

        XCTAssertNil(value)
    }

    func testExecuteMapsThrownCommandError() throws {
        let command = try TestScannerCommand(error: MlKitPluginError.invalidArguments)
        var value: Any?

        command.execute(FlutterMethodCall(methodName: "test", arguments: nil)) {
            value = $0
        }

        let error = value as? FlutterError
        XCTAssertEqual(error?.code, MlKitPluginError.invalidArguments.rawValue)
        XCTAssertEqual(error?.message, MlKitPluginError.invalidArguments.localizedDescription)
    }
}
