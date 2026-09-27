import Flutter
import XCTest
@testable import mlkit_scanner

final class ToggleFlashCommandTests: XCTestCase {
    func testAppliesArgumentsWithoutViewOrSubscriptionIdentifiers() {
        let device = RecordingScannerHardware()
        var replies = 0
        ToggleFlashCommand(scannerDevice: device).execute(
            FlutterMethodCall(methodName: "toggleFlash", arguments: ["value": true])
        ) {
            XCTAssertNil($0)
            replies += 1
        }
        XCTAssertEqual(replies, 1)
        XCTAssertEqual(device.torchEnabled, true)
    }

    func testMalformedArgumentsDoNotReachScanner() {
        let device = RecordingScannerHardware()
        var result: Any?
        ToggleFlashCommand(scannerDevice: device).execute(
            FlutterMethodCall(methodName: "toggleFlash", arguments: ["value": 1])
        ) { result = $0 }
        XCTAssertEqual((result as? FlutterError)?.code, MlKitPluginError.invalidArguments.rawValue)
        XCTAssertNil(device.torchEnabled)
    }
}
