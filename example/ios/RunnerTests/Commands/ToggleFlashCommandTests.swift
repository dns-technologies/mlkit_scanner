import Flutter
import XCTest
@testable import mlkit_scanner

final class ToggleFlashCommandTests: XCTestCase {
    func testAppliesArgumentsWithoutViewOrSubscriptionIdentifiers() throws {
        let device = try ScannerCommandFixture()
        defer { device.scanner.release() }
        var replies = 0
        ToggleFlashCommand(scanner: device.scanner).execute(
            FlutterMethodCall(methodName: "toggleFlash", arguments: ["value": true])
        ) {
            XCTAssertNil($0)
            replies += 1
        }
        XCTAssertEqual(replies, 1)
        XCTAssertEqual(device.camera.torchEnabled, true)
    }

    func testMalformedArgumentsDoNotReachScanner() throws {
        let device = try ScannerCommandFixture()
        defer { device.scanner.release() }
        var result: Any?
        ToggleFlashCommand(scanner: device.scanner).execute(
            FlutterMethodCall(methodName: "toggleFlash", arguments: ["value": 1])
        ) { result = $0 }
        XCTAssertEqual((result as? FlutterError)?.code, MlKitPluginError.invalidArguments.rawValue)
        XCTAssertNil(device.camera.torchEnabled)
    }
}
